package com.cezicola.card.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * One billing period, named by the month it closes in.
 *
 * <p>A cycle is a half-open interval: an item belongs to it when it occurred on
 * or after the start and strictly before the end. Closed intervals lose or
 * duplicate whatever falls exactly on a boundary, and an item billed twice is a
 * customer charged twice.
 *
 * <p>The reference is a fixed offset rather than a zone with a political
 * history. A cycle boundary that moves when a government changes a rule would
 * silently rebill the items on either side of it.
 */
public record BillingCycle(YearMonth month) {

    /** Brasília time, stated once so every boundary in the platform agrees. */
    public static final ZoneOffset ZONE = ZoneOffset.ofHours(-3);

    /**
     * Days between closing and the due date.
     *
     * <p>Ten is the usual window on a Brazilian card and is stated here rather
     * than derived, because a due date computed differently in two places is two
     * different claims on the same customer.
     */
    public static final int DAYS_UNTIL_DUE = 10;

    public BillingCycle {
        if (month == null) {
            throw new IllegalArgumentException("a billing cycle needs a month");
        }
    }

    public static BillingCycle of(int year, int month) {
        return new BillingCycle(YearMonth.of(year, month));
    }

    /** The cycle an instant falls in. */
    public static BillingCycle containing(Instant instant) {
        return new BillingCycle(YearMonth.from(LocalDate.ofInstant(instant, ZONE)));
    }

    /** First instant of the cycle, inclusive. */
    public Instant start() {
        return month.atDay(1).atStartOfDay(ZONE).toInstant();
    }

    /** First instant of the next cycle, exclusive. */
    public Instant end() {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
    }

    /** Whether an item that occurred at this instant belongs to this cycle. */
    public boolean contains(Instant occurredAt) {
        return !occurredAt.isBefore(start()) && occurredAt.isBefore(end());
    }

    /** When payment is due for this cycle. */
    public LocalDate dueDate() {
        return month.plusMonths(1).atDay(1).plusDays(DAYS_UNTIL_DUE - 1L);
    }

    public BillingCycle next() {
        return new BillingCycle(month.plusMonths(1));
    }

    /** Stable identifier, sortable as text: `2026-08`. */
    public String reference() {
        return month.toString();
    }

    public static BillingCycle parse(String reference) {
        return new BillingCycle(YearMonth.parse(reference));
    }
}
