package com.cezicola.card.application;

import com.cezicola.card.domain.BillingCycle;
import com.cezicola.card.domain.FundingSource;
import com.cezicola.card.domain.LedgerAccount;
import com.cezicola.card.domain.StatementStatus;
import com.cezicola.card.domain.StatementStateException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credit cycle, end to end, against the book.
 *
 * <p>A credit purchase is the one flow where the money does not move when the
 * customer spends it. The issuer pays the merchant and the customer owes it
 * back, and that debt survives until a statement bills it and a payment settles
 * it. Every step is asserted against the ledger rather than against a stored
 * total, because a stored total is exactly what would hide an error here.
 */
@QuarkusTest
class BillingCycleLedgerTest {

    @Inject FinanceService finance;
    @Inject BillingService billing;
    @Inject LedgerService ledger;
    @Inject CardService cards;

    private UUID issueCardFor(UUID tenant, UUID customer) {
        return cards.issueForCustomer(tenant, customer).id();
    }

    @Test
    void aCreditPurchaseOwesTheIssuerRatherThanSpendingTheCard() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        issueCardFor(tenant, customer);
        finance.topUp(tenant, customer, new BigDecimal("1000.00"));
        finance.loadCard(tenant, customer, new BigDecimal("400.00"));

        finance.purchase(tenant, customer, "RETAIL", new BigDecimal("250.00"), 1, FundingSource.CREDIT);

        // The prepaid balance is untouched: credit did not spend it.
        assertEquals(0, new BigDecimal("400.00").compareTo(finance.cardBalance(tenant, customer)));
        // The debt exists instead.
        assertEquals(0, new BigDecimal("250.00").compareTo(
                ledger.balanceOf(tenant, LedgerAccount.CUSTOMER_RECEIVABLE, customer)));
        assertTrue(ledger.unbalancedTransactions(tenant).isEmpty());
    }

    @Test
    void closingBillsTheCycleAndPayingSettlesIt() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        issueCardFor(tenant, customer);
        finance.topUp(tenant, customer, new BigDecimal("1000.00"));

        finance.purchase(tenant, customer, "RETAIL", new BigDecimal("200.00"), 1, FundingSource.CREDIT);
        finance.purchase(tenant, customer, "BAKERY", new BigDecimal("50.00"), 1, FundingSource.CREDIT);

        BillingCycle cycle = BillingCycle.containing(Instant.now());
        var statement = billing.close(tenant, customer, cycle);

        assertEquals(StatementStatus.CLOSED, statement.status());
        assertEquals(0, new BigDecimal("250.00").compareTo(statement.billedTotal()));
        assertEquals(2, billing.items(tenant, statement.id()).size());

        var paid = billing.pay(tenant, customer, statement.id(), new BigDecimal("250.00"));

        assertEquals(StatementStatus.PAID, paid.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(paid.balance()));
        // The wallet paid for it, and the debt is gone.
        assertEquals(0, new BigDecimal("750.00").compareTo(ledger.walletBalance(tenant, customer)));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                ledger.balanceOf(tenant, LedgerAccount.CUSTOMER_RECEIVABLE, customer)));
        assertTrue(ledger.unbalancedTransactions(tenant).isEmpty());
    }

    @Test
    void closingTwiceBillsTheSamePurchaseOnlyOnce() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        issueCardFor(tenant, customer);
        finance.purchase(tenant, customer, "RETAIL", new BigDecimal("120.00"), 1, FundingSource.CREDIT);

        BillingCycle cycle = BillingCycle.containing(Instant.now());
        var first = billing.close(tenant, customer, cycle);
        var second = billing.close(tenant, customer, cycle);

        // A retried close returns the statement it already produced rather than
        // billing the customer for the same purchase again.
        assertEquals(first.id(), second.id());
        assertEquals(0, new BigDecimal("120.00").compareTo(second.billedTotal()));
        assertEquals(1, billing.items(tenant, second.id()).size());
    }

    @Test
    void aPrepaidPurchaseIsNeverBilled() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        issueCardFor(tenant, customer);
        finance.topUp(tenant, customer, new BigDecimal("500.00"));
        finance.loadCard(tenant, customer, new BigDecimal("500.00"));

        finance.purchase(tenant, customer, "RETAIL", new BigDecimal("100.00"), 1);

        var statement = billing.close(tenant, customer, BillingCycle.containing(Instant.now()));

        // It was paid for when it happened. Billing it again would charge twice.
        assertEquals(0, BigDecimal.ZERO.compareTo(statement.billedTotal()));
        assertEquals(StatementStatus.PAID, statement.status());
        assertTrue(billing.items(tenant, statement.id()).isEmpty());
    }

    @Test
    void payingMoreThanIsOwedIsRefusedAndMovesNoMoney() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        issueCardFor(tenant, customer);
        finance.topUp(tenant, customer, new BigDecimal("1000.00"));
        finance.purchase(tenant, customer, "RETAIL", new BigDecimal("100.00"), 1, FundingSource.CREDIT);
        var statement = billing.close(tenant, customer, BillingCycle.containing(Instant.now()));

        assertThrows(StatementStateException.class,
                () -> billing.pay(tenant, customer, statement.id(), new BigDecimal("150.00")));

        // The wallet is untouched: a refused payment is not a partial one.
        assertEquals(0, new BigDecimal("1000.00").compareTo(ledger.walletBalance(tenant, customer)));
        assertEquals(0, new BigDecimal("100.00").compareTo(
                ledger.balanceOf(tenant, LedgerAccount.CUSTOMER_RECEIVABLE, customer)));
    }

    @Test
    void payingWithoutTheFundsIsRefused() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        issueCardFor(tenant, customer);
        finance.purchase(tenant, customer, "RETAIL", new BigDecimal("300.00"), 1, FundingSource.CREDIT);
        var statement = billing.close(tenant, customer, BillingCycle.containing(Instant.now()));

        // The statement is owed, and the wallet is empty.
        assertThrows(InsufficientFundsException.class,
                () -> billing.pay(tenant, customer, statement.id(), new BigDecimal("300.00")));

        assertEquals(0, new BigDecimal("300.00").compareTo(
                ledger.balanceOf(tenant, LedgerAccount.CUSTOMER_RECEIVABLE, customer)));
    }

    @Test
    void aPartialPaymentLeavesTheRestOwedInTheBook() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        issueCardFor(tenant, customer);
        finance.topUp(tenant, customer, new BigDecimal("1000.00"));
        finance.purchase(tenant, customer, "RETAIL", new BigDecimal("400.00"), 1, FundingSource.CREDIT);
        var statement = billing.close(tenant, customer, BillingCycle.containing(Instant.now()));

        var afterPayment = billing.pay(tenant, customer, statement.id(), new BigDecimal("150.00"));

        assertEquals(StatementStatus.PARTIALLY_PAID, afterPayment.status());
        assertEquals(0, new BigDecimal("250.00").compareTo(afterPayment.balance()));
        // The book agrees with the statement rather than merely resembling it.
        assertEquals(0, new BigDecimal("250.00").compareTo(
                ledger.balanceOf(tenant, LedgerAccount.CUSTOMER_RECEIVABLE, customer)));
        assertEquals(0, new BigDecimal("850.00").compareTo(ledger.walletBalance(tenant, customer)));
    }

    @Test
    void creditIsRefusedBeyondTheLimit() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        issueCardFor(tenant, customer);

        // Self-service issues a R$ 5.000,00 limit, so this is the first refusal
        // that is about willingness to lend rather than about an empty account.
        var refused = assertThrows(InsufficientFundsException.class,
                () -> finance.purchase(tenant, customer, "RETAIL", new BigDecimal("5001.00"), 1,
                        FundingSource.CREDIT));
        assertTrue(refused.getMessage().contains("credit limit"), refused.getMessage());

        assertEquals(0, BigDecimal.ZERO.compareTo(
                ledger.balanceOf(tenant, LedgerAccount.CUSTOMER_RECEIVABLE, customer)));
    }

    @Test
    void availableCreditFallsWithSpendingAndReturnsWithPayment() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        issueCardFor(tenant, customer);
        finance.topUp(tenant, customer, new BigDecimal("2000.00"));
        BigDecimal limit = new BigDecimal("5000.00");

        assertEquals(0, limit.compareTo(billing.availableCredit(tenant, customer, limit)));

        finance.purchase(tenant, customer, "RETAIL", new BigDecimal("1200.00"), 1, FundingSource.CREDIT);
        assertEquals(0, new BigDecimal("3800.00").compareTo(billing.availableCredit(tenant, customer, limit)));

        var statement = billing.close(tenant, customer, BillingCycle.containing(Instant.now()));
        // Billing moves nothing: the debt already existed, the statement only
        // names it. Available credit is unchanged by being told about it.
        assertEquals(0, new BigDecimal("3800.00").compareTo(billing.availableCredit(tenant, customer, limit)));

        billing.pay(tenant, customer, statement.id(), new BigDecimal("1200.00"));
        assertEquals(0, limit.compareTo(billing.availableCredit(tenant, customer, limit)));
    }

    @Test
    void everyCycleMovementLeavesTheBookBalanced() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, new BigDecimal("0.0199"));
        issueCardFor(tenant, customer);
        finance.topUp(tenant, customer, new BigDecimal("3000.00"));
        finance.loadCard(tenant, customer, new BigDecimal("500.00"));

        finance.purchase(tenant, customer, "RETAIL", new BigDecimal("300.00"), 6, FundingSource.CREDIT);
        finance.purchase(tenant, customer, "BAKERY", new BigDecimal("40.00"), 1, FundingSource.CREDIT);
        finance.purchase(tenant, customer, "SHOPPING", new BigDecimal("100.00"), 1);

        var statement = billing.close(tenant, customer, BillingCycle.containing(Instant.now()));
        billing.pay(tenant, customer, statement.id(), statement.balance());

        assertTrue(ledger.unbalancedTransactions(tenant).isEmpty(),
                "the cycle wrote an entry whose debits and credits differ");
        assertTrue(ledger.reconcileWallets(tenant).isEmpty(),
                "the wallet projection drifted from the book across the cycle");
        assertEquals(0, BigDecimal.ZERO.compareTo(
                ledger.balanceOf(tenant, LedgerAccount.CUSTOMER_RECEIVABLE, customer)));
    }
}
