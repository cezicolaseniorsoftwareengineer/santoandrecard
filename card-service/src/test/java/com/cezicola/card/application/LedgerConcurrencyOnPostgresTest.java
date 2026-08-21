package com.cezicola.card.application;

import com.cezicola.card.domain.CardProduct;
import com.cezicola.card.domain.FundingSource;
import com.cezicola.card.domain.LedgerAccount;
import com.cezicola.card.support.PostgresProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Money under concurrency, on the database that will actually hold it.
 *
 * <p>{@code FinanceService} locks the wallet row as a per-customer mutex because
 * the spendable figure is a sum over postings and has no row of its own to lock.
 * That claim is only worth as much as the engine enforcing it. H2 accepts
 * {@code PESSIMISTIC_WRITE} and interprets it its own way, so the fast suite can
 * pass while two concurrent purchases both read the same balance and both settle.
 * These tests run the same code against real PostgreSQL and let the race happen.
 *
 * <p>The assertion is deliberately exact rather than "no negative balance": an
 * overdraft is the loud failure, but a lock that serialises too much and refuses
 * a purchase the customer could afford is a defect too.
 */
@QuarkusTest
@TestProfile(PostgresProfile.class)
class LedgerConcurrencyOnPostgresTest {

    private static final int ATTEMPTS = 20;

    @Inject FinanceService finance;
    @Inject LedgerService ledger;
    @Inject CardService cards;

    @Test
    void concurrentPurchasesSpendACardExactlyOnce() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        // No interest, so the arithmetic of the test is the arithmetic of the
        // race and not of the pricing.
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        finance.topUp(tenant, customer, new BigDecimal("500.00"));
        finance.loadCard(tenant, customer, new BigDecimal("500.00"));

        AtomicInteger refused = new AtomicInteger();
        var outcomes = race(ATTEMPTS, () -> {
            try {
                finance.purchase(tenant, customer, "RETAIL", new BigDecimal("100.00"), 1);
                return true;
            } catch (InsufficientFundsException expected) {
                refused.incrementAndGet();
                return false;
            }
        });

        long settled = outcomes.stream().filter(Boolean::booleanValue).count();
        assertEquals(5, settled, "the card held R$ 500,00 and every purchase cost R$ 100,00");
        assertEquals(ATTEMPTS - 5, refused.get(), "every purchase past the fifth must be refused, not lost");

        assertEquals(0, BigDecimal.ZERO.compareTo(finance.cardBalance(tenant, customer)),
                "the card is spent to exactly zero, never past it");
        assertTrue(ledger.unbalancedTransactions(tenant).isEmpty(),
                "a concurrent purchase wrote an unbalanced entry");
        assertTrue(ledger.reconcileWallets(tenant).isEmpty(),
                "the wallet projection drifted from the book under concurrency");
    }

    @Test
    void concurrentCardLoadsCannotOverdrawTheWallet() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.topUp(tenant, customer, new BigDecimal("300.00"));

        var outcomes = race(ATTEMPTS, () -> {
            try {
                finance.loadCard(tenant, customer, new BigDecimal("100.00"));
                return true;
            } catch (InsufficientFundsException expected) {
                return false;
            }
        });

        assertEquals(3, outcomes.stream().filter(Boolean::booleanValue).count(),
                "R$ 300,00 in the wallet funds exactly three loads of R$ 100,00");
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.walletBalance(tenant, customer)));
        // Nothing was created or destroyed: the money only changed account.
        assertEquals(0, new BigDecimal("300.00").compareTo(finance.cardBalance(tenant, customer)));
        assertTrue(ledger.reconcileWallets(tenant).isEmpty());
    }

    /**
     * The credit limit under the same race, for a customer who never funded a wallet.
     *
     * <p>This is the case the other two could not reach. The mutex is the wallet
     * row, and that row was created by the first top-up, so a customer who only
     * ever bought on credit had none: the lock resolved to nothing and every
     * concurrent purchase read the same outstanding receivable before any of
     * them had written one. The limit held only because nothing had raced it.
     *
     * <p>No top-up here on purpose. The card alone carries the limit, and the
     * assertion is exact for the same reason as above: an over-limit purchase is
     * the loud failure, and refusing a purchase that fits under the limit is a
     * defect too.
     */
    @Test
    void concurrentCreditPurchasesCannotExceedTheLimitWithoutAWallet() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        cards.create(new CreateCardCommand(tenant, customer, new BigDecimal("500.00"),
                CardProduct.PLATINUM, "credit-race-" + customer));

        AtomicInteger refused = new AtomicInteger();
        var outcomes = race(ATTEMPTS, () -> {
            try {
                finance.purchase(tenant, customer, "RETAIL", new BigDecimal("100.00"), 1,
                        FundingSource.CREDIT);
                return true;
            } catch (InsufficientFundsException expected) {
                refused.incrementAndGet();
                return false;
            }
        });

        long settled = outcomes.stream().filter(Boolean::booleanValue).count();
        assertEquals(5, settled, "a R$ 500,00 limit funds exactly five credit purchases of R$ 100,00");
        assertEquals(ATTEMPTS - 5, refused.get(), "every purchase past the fifth must be refused, not lost");

        assertEquals(0, new BigDecimal("500.00").compareTo(
                        ledger.balanceOf(tenant, LedgerAccount.CUSTOMER_RECEIVABLE, customer)),
                "the customer owes exactly the limit, never more");
        assertTrue(ledger.unbalancedTransactions(tenant).isEmpty(),
                "a concurrent credit purchase wrote an unbalanced entry");
    }

    /**
     * Releases every thread at the same instant. Submitting the tasks one by one
     * would let the first finish before the last starts, and the race the test
     * exists to provoke would never happen.
     */
    private static List<Boolean> race(int threads, Callable<Boolean> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return attempt.call();
                }));
            }
            start.countDown();
            List<Boolean> outcomes = new java.util.ArrayList<>();
            for (Future<Boolean> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
