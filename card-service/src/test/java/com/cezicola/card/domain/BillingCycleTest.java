package com.cezicola.card.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cycle decides which items a customer is billed for, so its boundaries are
 * the difference between charging once and charging twice.
 */
class BillingCycleTest {

    private static final BillingCycle AUGUST = BillingCycle.of(2026, 8);

    @Test
    void theIntervalIsHalfOpen() {
        // The first instant belongs to the cycle.
        assertTrue(AUGUST.contains(AUGUST.start()));
        // The instant that ends it belongs to the next one, not to both.
        assertFalse(AUGUST.contains(AUGUST.end()));
        assertTrue(AUGUST.next().contains(AUGUST.end()));
    }

    @Test
    void consecutiveCyclesLeaveNoGapAndNoOverlap() {
        assertEquals(AUGUST.end(), AUGUST.next().start());
    }

    @Test
    void boundariesFollowBrasiliaRatherThanUtc() {
        // 2026-08-01T00:00 in Brasília is 03:00 UTC. An item at 02:00 UTC is
        // still July for the customer, and billing it in August would move a
        // purchase into a cycle the customer never saw it in.
        assertEquals(Instant.parse("2026-08-01T03:00:00Z"), AUGUST.start());
        assertFalse(AUGUST.contains(Instant.parse("2026-08-01T02:59:59Z")));
        assertTrue(BillingCycle.of(2026, 7).contains(Instant.parse("2026-08-01T02:59:59Z")));
    }

    @Test
    void anInstantIsClassifiedIntoExactlyOneCycle() {
        BillingCycle cycle = BillingCycle.containing(Instant.parse("2026-08-15T12:00:00Z"));
        assertEquals(AUGUST, cycle);
    }

    @Test
    void paymentIsDueTenDaysAfterTheCycleCloses() {
        assertEquals(LocalDate.of(2026, 9, 10), AUGUST.dueDate());
    }

    @Test
    void decemberRollsIntoTheNextYear() {
        BillingCycle december = BillingCycle.of(2026, 12);
        assertEquals(BillingCycle.of(2027, 1), december.next());
        assertEquals(LocalDate.of(2027, 1, 10), december.dueDate());
    }

    @Test
    void theReferenceRoundTrips() {
        assertEquals("2026-08", AUGUST.reference());
        assertEquals(AUGUST, BillingCycle.parse(AUGUST.reference()));
    }
}
