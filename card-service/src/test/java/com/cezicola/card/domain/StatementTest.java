package com.cezicola.card.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement is a claim on a customer, so what it refuses matters more than
 * what it allows.
 */
class StatementTest {

    private static final BillingCycle AUGUST = BillingCycle.of(2026, 8);
    private static final Instant CLOSED_AT = Instant.parse("2026-09-01T03:00:00Z");

    private Statement openStatement() {
        return Statement.open(UUID.randomUUID(), UUID.randomUUID(), AUGUST);
    }

    private Statement closedAt(String total) {
        Statement statement = openStatement();
        statement.close(new BigDecimal(total), CLOSED_AT);
        return statement;
    }

    @Test
    void closingFixesTheTotalAndTheDueDate() {
        Statement statement = closedAt("1200.00");

        assertEquals(StatementStatus.CLOSED, statement.status());
        assertEquals(0, new BigDecimal("1200.00").compareTo(statement.billedTotal()));
        assertEquals(0, new BigDecimal("1200.00").compareTo(statement.balance()));
        // Ten days after the cycle ends, counted from the first of the next month.
        assertEquals(LocalDate.of(2026, 9, 10), statement.dueDate());
    }

    @Test
    void anEmptyCycleIsStillClosed() {
        Statement statement = closedAt("0.00");

        // A period with no record is a period nobody can answer for, even when
        // the answer is that nothing happened.
        assertEquals(StatementStatus.PAID, statement.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(statement.balance()));
    }

    @Test
    void aStatementCannotBeClosedTwice() {
        Statement statement = closedAt("500.00");

        var refused = assertThrows(StatementStateException.class,
                () -> statement.close(new BigDecimal("900.00"), CLOSED_AT));
        assertTrue(refused.getMessage().contains("CLOSED"), refused.getMessage());
        // The figure the customer was told stands.
        assertEquals(0, new BigDecimal("500.00").compareTo(statement.billedTotal()));
    }

    @Test
    void partialPaymentLeavesTheRestOwed() {
        Statement statement = closedAt("300.00");

        statement.pay(new BigDecimal("100.00"), LocalDate.of(2026, 9, 5));

        assertEquals(StatementStatus.PARTIALLY_PAID, statement.status());
        assertEquals(0, new BigDecimal("200.00").compareTo(statement.balance()));
    }

    @Test
    void payingTheBalanceSettlesIt() {
        Statement statement = closedAt("300.00");

        statement.pay(new BigDecimal("100.00"), LocalDate.of(2026, 9, 5));
        statement.pay(new BigDecimal("200.00"), LocalDate.of(2026, 9, 6));

        assertEquals(StatementStatus.PAID, statement.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(statement.balance()));
    }

    @Test
    void payingMoreThanIsOwedIsRefusedRatherThanTruncated() {
        Statement statement = closedAt("100.00");

        var refused = assertThrows(StatementStateException.class,
                () -> statement.pay(new BigDecimal("150.00"), LocalDate.of(2026, 9, 5)));
        assertTrue(refused.getMessage().contains("100.00"), refused.getMessage());

        // Applying part of it and reporting success is how money disappears
        // between a payment screen and a ledger.
        assertEquals(0, BigDecimal.ZERO.compareTo(statement.paidTotal()));
        assertEquals(StatementStatus.CLOSED, statement.status());
    }

    @Test
    void aSettledStatementRefusesFurtherPayment() {
        Statement statement = closedAt("100.00");
        statement.pay(new BigDecimal("100.00"), LocalDate.of(2026, 9, 5));

        // Money arriving against a settled claim belongs to the next cycle.
        assertThrows(StatementStateException.class,
                () -> statement.pay(new BigDecimal("10.00"), LocalDate.of(2026, 9, 6)));
    }

    @Test
    void anOpenStatementCannotBePaid() {
        Statement statement = openStatement();

        // Nothing is owed until the cycle is billed.
        assertThrows(StatementStateException.class,
                () -> statement.pay(new BigDecimal("10.00"), LocalDate.of(2026, 8, 15)));
    }

    @Test
    void aBalanceOutstandingAfterTheDueDateIsOverdue() {
        Statement statement = closedAt("400.00");

        assertTrue(statement.markOverdueIfDue(LocalDate.of(2026, 9, 11)));
        assertEquals(StatementStatus.OVERDUE, statement.status());
        // Idempotent: a sweep that runs twice reports work once.
        assertFalse(statement.markOverdueIfDue(LocalDate.of(2026, 9, 12)));
    }

    @Test
    void onTheDueDateItselfNothingIsOverdue() {
        Statement statement = closedAt("400.00");

        // The customer has the whole of the due date to pay.
        assertFalse(statement.markOverdueIfDue(LocalDate.of(2026, 9, 10)));
        assertEquals(StatementStatus.CLOSED, statement.status());
    }

    @Test
    void aStatementPaidInFullNeverGoesOverdue() {
        Statement statement = closedAt("400.00");
        statement.pay(new BigDecimal("400.00"), LocalDate.of(2026, 9, 5));

        assertFalse(statement.markOverdueIfDue(LocalDate.of(2026, 12, 1)));
        assertEquals(StatementStatus.PAID, statement.status());
    }

    @Test
    void payingAnOverdueStatementInPartKeepsItOverdue() {
        Statement statement = closedAt("400.00");
        statement.markOverdueIfDue(LocalDate.of(2026, 9, 11));

        statement.pay(new BigDecimal("100.00"), LocalDate.of(2026, 9, 12));

        // Still late, and still owed: paying some of it does not undo the delay.
        assertEquals(StatementStatus.OVERDUE, statement.status());
        assertEquals(0, new BigDecimal("300.00").compareTo(statement.balance()));
    }

    @Test
    void moneyCarryingMoreThanTwoDecimalsIsRefused() {
        Statement statement = openStatement();

        assertThrows(StatementStateException.class,
                () -> statement.close(new BigDecimal("10.005"), CLOSED_AT));
    }

    @Test
    void aStatementCannotBillANegativeTotal() {
        Statement statement = openStatement();

        assertThrows(StatementStateException.class,
                () -> statement.close(new BigDecimal("-1.00"), CLOSED_AT));
    }
}
