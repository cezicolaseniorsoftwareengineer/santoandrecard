package com.cezicola.card.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What a customer owes for one cycle.
 *
 * <p>The rules that matter are enforced here rather than in the service, because
 * they are properties of the claim itself: a statement cannot be paid beyond its
 * balance, cannot be reopened once closed, and cannot be closed twice. A service
 * that forgets one of those produces a customer who paid more than they owed and
 * a ledger that has to be reconciled by hand.
 *
 * <p>Amounts are the total billed and the total paid. The balance is derived
 * from them rather than stored, so there is no third number to keep in step —
 * and a stored balance that disagrees with its own two components is the classic
 * way a statement stops being trustworthy.
 */
public class Statement {

    private final UUID id;
    private final UUID tenantId;
    private final UUID customerId;
    private final BillingCycle cycle;
    private StatementStatus status;
    private BigDecimal billedTotal;
    private BigDecimal paidTotal;
    private LocalDate dueDate;
    private Instant closedAt;

    public Statement(UUID id, UUID tenantId, UUID customerId, BillingCycle cycle,
                     StatementStatus status, BigDecimal billedTotal, BigDecimal paidTotal,
                     LocalDate dueDate, Instant closedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.cycle = cycle;
        this.status = status;
        this.billedTotal = requireMoney(billedTotal, "billedTotal");
        this.paidTotal = requireMoney(paidTotal, "paidTotal");
        this.dueDate = dueDate;
        this.closedAt = closedAt;
        if (this.paidTotal.compareTo(this.billedTotal) > 0) {
            throw new StatementStateException("a statement cannot have been paid more than it billed");
        }
    }

    /** A fresh, empty cycle. */
    public static Statement open(UUID tenantId, UUID customerId, BillingCycle cycle) {
        return new Statement(UUID.randomUUID(), tenantId, customerId, cycle,
                StatementStatus.OPEN, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
    }

    /**
     * Bills the cycle.
     *
     * <p>A cycle with nothing in it is still closed, and closed at zero: skipping
     * it would leave the period without a record, and a customer asking what
     * happened in August deserves an answer even when the answer is nothing.
     */
    public void close(BigDecimal total, Instant at) {
        if (status != StatementStatus.OPEN) {
            throw new StatementStateException("only an open statement can be closed, this one is " + status);
        }
        requireMoney(total, "total");
        if (total.signum() < 0) {
            throw new StatementStateException("a statement cannot bill a negative total");
        }
        this.billedTotal = total;
        this.dueDate = cycle.dueDate();
        this.closedAt = at;
        this.status = total.signum() == 0 ? StatementStatus.PAID : StatementStatus.CLOSED;
    }

    /**
     * Applies a payment and returns what was actually applied.
     *
     * <p>An overpayment is refused rather than truncated. Silently accepting more
     * than the balance and applying part of it leaves the caller believing the
     * whole amount landed, which is how money goes missing between a payment
     * screen and a ledger.
     */
    public BigDecimal pay(BigDecimal amount, LocalDate today) {
        if (!status.acceptsPayment()) {
            throw new StatementStateException("a statement that is " + status + " does not accept payment");
        }
        requireMoney(amount, "amount");
        if (amount.signum() <= 0) {
            throw new StatementStateException("a payment must be positive");
        }
        BigDecimal outstanding = balance();
        if (amount.compareTo(outstanding) > 0) {
            throw new StatementStateException(
                    "the payment of " + amount + " is more than the " + outstanding + " outstanding");
        }
        this.paidTotal = paidTotal.add(amount);
        this.status = balance().signum() == 0
                ? StatementStatus.PAID
                : overdueOn(today) ? StatementStatus.OVERDUE : StatementStatus.PARTIALLY_PAID;
        return amount;
    }

    /**
     * Marks the statement overdue when its due date has passed with a balance.
     *
     * <p>Returns whether anything changed, so a sweep over many statements can
     * report what it actually did rather than how many it looked at.
     */
    public boolean markOverdueIfDue(LocalDate today) {
        if (!status.acceptsPayment() || !overdueOn(today)) {
            return false;
        }
        if (status == StatementStatus.OVERDUE) {
            return false;
        }
        this.status = StatementStatus.OVERDUE;
        return true;
    }

    private boolean overdueOn(LocalDate today) {
        return dueDate != null && today.isAfter(dueDate) && balance().signum() > 0;
    }

    /** What is still owed. Derived, never stored. */
    public BigDecimal balance() {
        return billedTotal.subtract(paidTotal);
    }

    private static BigDecimal requireMoney(BigDecimal value, String what) {
        if (value == null) {
            throw new StatementStateException(what + " is required");
        }
        if (value.scale() > 2) {
            throw new StatementStateException(what + " carries more than two decimals: " + value);
        }
        return value;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID customerId() { return customerId; }
    public BillingCycle cycle() { return cycle; }
    public StatementStatus status() { return status; }
    public BigDecimal billedTotal() { return billedTotal; }
    public BigDecimal paidTotal() { return paidTotal; }
    public LocalDate dueDate() { return dueDate; }
    public Instant closedAt() { return closedAt; }
}
