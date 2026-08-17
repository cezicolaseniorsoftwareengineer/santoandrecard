package com.cezicola.card.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JournalEntryTest {
    private static final UUID CUSTOMER = UUID.randomUUID();

    @Test
    void acceptsAnEntryWhoseDebitsEqualItsCredits() {
        assertDoesNotThrow(() -> new JournalEntry(
                JournalEntry.Kind.TOP_UP, "Adição de saldo", null,
                List.of(
                        JournalEntry.Posting.debit(LedgerAccount.FUNDING, null, new BigDecimal("100.00")),
                        JournalEntry.Posting.credit(LedgerAccount.CUSTOMER_WALLET, CUSTOMER, new BigDecimal("100.00")))));
    }

    @Test
    void acceptsCreditsSplitAcrossSeveralAccounts() {
        assertDoesNotThrow(() -> new JournalEntry(
                JournalEntry.Kind.PURCHASE, "Compra parcelada", UUID.randomUUID(),
                List.of(
                        JournalEntry.Posting.debit(LedgerAccount.CUSTOMER_WALLET, CUSTOMER, new BigDecimal("675.30")),
                        JournalEntry.Posting.credit(LedgerAccount.MERCHANT_PAYABLE, null, new BigDecimal("600.00")),
                        JournalEntry.Posting.credit(LedgerAccount.INTEREST_REVENUE, null, new BigDecimal("75.30")))));
    }

    @Test
    void rejectsAnEntryThatDoesNotBalance() {
        assertThrows(UnbalancedTransactionException.class, () -> new JournalEntry(
                JournalEntry.Kind.PURCHASE, "Compra", null,
                List.of(
                        JournalEntry.Posting.debit(LedgerAccount.CUSTOMER_WALLET, CUSTOMER, new BigDecimal("100.00")),
                        // One cent short: the entry must not be recordable at all.
                        JournalEntry.Posting.credit(LedgerAccount.MERCHANT_PAYABLE, null, new BigDecimal("99.99")))));
    }

    @Test
    void rejectsASinglePostingBecauseItCannotHaveTwoSides() {
        assertThrows(UnbalancedTransactionException.class, () -> new JournalEntry(
                JournalEntry.Kind.TOP_UP, "Metade de um lançamento", null,
                List.of(JournalEntry.Posting.debit(LedgerAccount.FUNDING, null, new BigDecimal("10.00")))));
    }

    @Test
    void rejectsANegativeOrZeroPosting() {
        assertThrows(UnbalancedTransactionException.class,
                () -> JournalEntry.Posting.debit(LedgerAccount.FUNDING, null, new BigDecimal("-1.00")));
        assertThrows(UnbalancedTransactionException.class,
                () -> JournalEntry.Posting.debit(LedgerAccount.FUNDING, null, BigDecimal.ZERO));
    }

    @Test
    void rejectsFractionsOfACentBecauseTheyCannotBeSettled() {
        assertThrows(UnbalancedTransactionException.class,
                () -> JournalEntry.Posting.debit(LedgerAccount.FUNDING, null, new BigDecimal("10.001")));
    }

    @Test
    void requiresACustomerExactlyOnPerCustomerAccounts() {
        assertThrows(UnbalancedTransactionException.class,
                () -> JournalEntry.Posting.debit(LedgerAccount.CUSTOMER_WALLET, null, new BigDecimal("10.00")));
        assertThrows(UnbalancedTransactionException.class,
                () -> JournalEntry.Posting.debit(LedgerAccount.FUNDING, CUSTOMER, new BigDecimal("10.00")));
    }
}
