package com.cezicola.card.application;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wallet column is a projection kept so a purchase can lock one row instead
 * of summing a table that only grows. It is legitimate only while it still agrees
 * with the ledger, so that agreement is asserted rather than assumed.
 */
@QuarkusTest
class LedgerReconciliationTest {
    @Inject FinanceService finance;
    @Inject LedgerService ledger;

    @Test
    void everyMovementLeavesTheProjectionAgreeingWithTheBook() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, new BigDecimal("0.0199"));

        finance.topUp(tenant, customer, new BigDecimal("2000.00"));
        finance.loadCard(tenant, customer, new BigDecimal("1500.00"));
        purchase(tenant, customer, "RETAIL", "600.00", 6);
        finance.topUp(tenant, customer, new BigDecimal("150.00"));
        finance.loadCard(tenant, customer, new BigDecimal("100.00"));
        purchase(tenant, customer, "BAKERY", "40.00", 1);

        assertTrue(ledger.reconcileWallets(tenant).isEmpty(),
                "the cached wallet balance drifted from the ledger: " + ledger.reconcileWallets(tenant));
        assertTrue(ledger.unbalancedTransactions(tenant).isEmpty(),
                "the ledger contains transactions whose debits and credits differ");
    }

    @Test
    void theLedgerAndTheProjectionReportTheSameFigure() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, new BigDecimal("0.10"));

        finance.topUp(tenant, customer, new BigDecimal("200.00"));
        finance.loadCard(tenant, customer, new BigDecimal("200.00"));
        var result = purchase(tenant, customer, "SHOPPING", "100.00", 2);

        // The card pays: 100.00 principal plus 21.00 interest leaves 79.00 of the
        // 200.00 loaded onto it, and the wallet it was loaded from is now empty.
        assertEquals(0, new BigDecimal("79.00").compareTo(result.remainingCardBalance()));
        assertEquals(0, new BigDecimal("79.00").compareTo(finance.cardBalance(tenant, customer)));
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.walletBalance(tenant, customer)));
    }

    @Test
    void aRefusedPurchaseLeavesNothingInTheBook() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.topUp(tenant, customer, new BigDecimal("100.00"));

        try {
            // Fails closed at the merchant gateway, after the amount is known.
            finance.purchase(tenant, customer, "NETWORK_FAILURE", new BigDecimal("10.00"), 1);
        } catch (RuntimeException expected) {
            // The refusal is the point of the test.
        }

        assertEquals(0, new BigDecimal("100.00").compareTo(ledger.walletBalance(tenant, customer)),
                "a refused purchase moved money in the ledger");
        assertTrue(ledger.reconcileWallets(tenant).isEmpty());
    }

    @Test
    void interestIsRecordedAsRevenueRatherThanHiddenInTheTotal() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, new BigDecimal("0.10"));
        finance.topUp(tenant, customer, new BigDecimal("500.00"));
        finance.loadCard(tenant, customer, new BigDecimal("300.00"));
        purchase(tenant, customer, "SHOPPING", "100.00", 2);

        var statement = ledger.walletStatement(tenant, customer, 10);
        assertEquals(2, statement.size());
        // Most recent first: the card load left the wallet, the top-up entered it.
        // The purchase itself is not a wallet movement any more — the card pays.
        assertEquals(0, new BigDecimal("-300.00").compareTo(statement.get(0).signedAmount()));
        assertEquals(0, new BigDecimal("500.00").compareTo(statement.get(1).signedAmount()));

        // 121.00 of the 300.00 on the card was spent, and the 21.00 of interest is
        // revenue rather than a smaller net movement.
        assertEquals(0, new BigDecimal("179.00").compareTo(finance.cardBalance(tenant, customer)));
    }

    /**
     * The merchant circuit breaker is application-scoped, so a test that opens it
     * on purpose leaves it open for whatever runs next. A refused purchase records
     * nothing, which {@link #aRefusedPurchaseLeavesNothingInTheBook} asserts, so
     * retrying is safe and keeps this class about the ledger rather than about the
     * breaker.
     */
    private FinanceService.PurchaseView purchase(UUID tenant, UUID customer, String category,
                                                 String amount, int installments) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                return finance.purchase(tenant, customer, category, new BigDecimal(amount), installments);
            } catch (MerchantAuthorizationUnavailableException unavailable) {
                last = unavailable;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw unavailable;
                }
            }
        }
        throw last;
    }
}
