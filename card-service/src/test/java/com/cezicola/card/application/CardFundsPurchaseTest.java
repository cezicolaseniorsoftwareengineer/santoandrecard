package com.cezicola.card.application;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The card is what pays.
 *
 * <p>Purchases used to be taken from the wallet while the card held a separate
 * balance of its own, so loading the card moved money out of reach: a customer
 * with R$ 3.000 on the card and an empty wallet was refused a R$ 100 purchase.
 * The spendable figure and the card balance are now the same number.
 */
@QuarkusTest
class CardFundsPurchaseTest {
    @Inject FinanceService finance;
    @Inject LedgerService ledger;

    @Test
    void whatIsOnTheCardIsWhatCanBeSpent() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);

        finance.topUp(tenant, customer, new BigDecimal("4000.00"));
        var loaded = finance.loadCard(tenant, customer, new BigDecimal("3000.00"));

        assertEquals(0, new BigDecimal("1000.00").compareTo(loaded.walletBalance()));
        assertEquals(0, new BigDecimal("3000.00").compareTo(loaded.cardBalance()));

        var purchase = purchase(tenant, customer, "SHOPPING", "100.00", 1);

        assertEquals(0, new BigDecimal("2900.00").compareTo(purchase.remainingCardBalance()));
        assertEquals(0, new BigDecimal("2900.00").compareTo(finance.cardBalance(tenant, customer)));
        // The wallet is untouched by a purchase: it funded the card, not the sale.
        assertEquals(0, new BigDecimal("1000.00").compareTo(ledger.walletBalance(tenant, customer)));
    }

    @Test
    void moneyLeftInTheWalletCannotBeSpentOnTheCard() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        finance.topUp(tenant, customer, new BigDecimal("500.00"));

        // Nothing has been loaded onto the card, so there is nothing to spend even
        // though the wallet is funded.
        var refused = assertThrows(InsufficientFundsException.class,
                () -> finance.purchase(tenant, customer, "SHOPPING", new BigDecimal("100.00"), 1));
        assertTrue(refused.getMessage().contains("card"), refused.getMessage());

        assertEquals(0, new BigDecimal("500.00").compareTo(ledger.walletBalance(tenant, customer)));
    }

    @Test
    void interestIsChargedToTheCardAndBookedAsRevenue() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, new BigDecimal("0.10"));
        finance.topUp(tenant, customer, new BigDecimal("500.00"));
        finance.loadCard(tenant, customer, new BigDecimal("500.00"));

        var purchase = purchase(tenant, customer, "SHOPPING", "100.00", 2);

        // 100.00 over two months at 10% is 121.00, all of it off the card.
        assertEquals(0, new BigDecimal("121.00").compareTo(purchase.total()));
        assertEquals(0, new BigDecimal("21.00").compareTo(purchase.interest()));
        assertEquals(0, new BigDecimal("379.00").compareTo(finance.cardBalance(tenant, customer)));
        assertEquals(0, new BigDecimal("0.10").compareTo(purchase.monthlyRate()),
                "the purchase must record the rate it was priced under");
        assertTrue(ledger.unbalancedTransactions(tenant).isEmpty());
    }

    @Test
    void thePlanRecordedOnThePurchaseAddsUpToItsTotal() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        finance.setInterestPolicy(tenant, new BigDecimal("0.02"));
        finance.topUp(tenant, customer, new BigDecimal("2000.00"));
        finance.loadCard(tenant, customer, new BigDecimal("2000.00"));

        var purchase = purchase(tenant, customer, "SHOPPING", "1000.00", 12);

        BigDecimal sum = purchase.installmentAmount()
                .multiply(BigDecimal.valueOf(purchase.installments() - 1L))
                .add(purchase.lastInstallmentAmount());
        assertEquals(0, purchase.total().compareTo(sum),
                "the instalments on the statement do not add up to the total");
    }

    /** The merchant gateway is a simulated network and refuses at random; retry through it. */
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
