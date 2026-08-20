package com.cezicola.card.domain;

/**
 * Where a statement is in its life.
 *
 * <p>The transitions are explicit because a statement is a claim on a customer,
 * and a claim that can move anywhere is not a claim. Closure is one-way: a
 * closed statement is what the customer was told they owe, and reopening it
 * would change a figure someone has already acted on. Corrections are made by
 * posting an adjustment onto the next cycle, never by editing a closed one.
 */
public enum StatementStatus {
    /** Accumulating items. The current cycle; nothing is owed yet. */
    OPEN,
    /** Billed. The total is fixed and the due date is set. */
    CLOSED,
    /** Some of it paid, and the rest still owed. */
    PARTIALLY_PAID,
    /** Paid in full. */
    PAID,
    /** Past its due date with a balance outstanding. */
    OVERDUE;

    /**
     * Whether money can still be applied to a statement in this state.
     *
     * <p>A paid statement refuses further payment rather than absorbing it: money
     * arriving against a settled claim belongs to the next cycle, and quietly
     * swallowing it is how a customer ends up with a credit nobody can find.
     */
    public boolean acceptsPayment() {
        return this == CLOSED || this == PARTIALLY_PAID || this == OVERDUE;
    }

    /** Whether the cycle is still gathering items. */
    public boolean isOpen() {
        return this == OPEN;
    }
}
