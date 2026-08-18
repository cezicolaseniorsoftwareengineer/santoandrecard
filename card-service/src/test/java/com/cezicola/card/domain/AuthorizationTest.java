package com.cezicola.card.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state machine, tested where it lives. These rules decide whether money may
 * move, so they are asserted against the aggregate directly rather than through
 * a service that could be bypassed by a future entry point.
 */
class AuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final Duration WEEK = Duration.ofDays(7);

    @Test
    void capturesWhatWasHeld() {
        Authorization captured = hold("100.00").capture(new BigDecimal("100.00"), NOW.plusSeconds(60));

        assertEquals(AuthorizationStatus.CAPTURED, captured.status());
        assertEquals(new BigDecimal("100.00"), captured.capturedAmount());
        assertEquals(0, captured.releasedAmount().signum());
    }

    @Test
    void capturesLessThanWasHeldAndReleasesTheRest() {
        // A merchant that ships half an order takes half the hold; the customer
        // must not stay short of the other half.
        Authorization captured = hold("100.00").capture(new BigDecimal("40.00"), NOW.plusSeconds(60));

        assertEquals(new BigDecimal("40.00"), captured.capturedAmount());
        assertEquals(new BigDecimal("60.00"), captured.releasedAmount());
    }

    @Test
    void refusesToCaptureMoreThanWasAuthorised() {
        assertThrows(AuthorizationStateException.class,
                () -> hold("100.00").capture(new BigDecimal("100.01"), NOW.plusSeconds(60)));
    }

    @Test
    void refusesToCaptureTwice() {
        Authorization captured = hold("100.00").capture(new BigDecimal("100.00"), NOW.plusSeconds(60));

        assertThrows(AuthorizationStateException.class,
                () -> captured.capture(new BigDecimal("100.00"), NOW.plusSeconds(120)));
    }

    @Test
    void refusesToCaptureAfterTheHoldExpired() {
        assertThrows(AuthorizationStateException.class,
                () -> hold("100.00").capture(new BigDecimal("100.00"), NOW.plus(WEEK)));
    }

    @Test
    void refusesToCaptureWhatWasReversed() {
        Authorization reversed = hold("100.00").reverse(NOW.plusSeconds(60));

        assertEquals(AuthorizationStatus.REVERSED, reversed.status());
        assertEquals(new BigDecimal("100.00"), reversed.releasedAmount());
        assertThrows(AuthorizationStateException.class,
                () -> reversed.capture(new BigDecimal("100.00"), NOW.plusSeconds(120)));
    }

    @Test
    void refusesToReverseWhatWasCaptured() {
        Authorization captured = hold("100.00").capture(new BigDecimal("100.00"), NOW.plusSeconds(60));

        assertThrows(AuthorizationStateException.class, () -> captured.reverse(NOW.plusSeconds(120)));
    }

    @Test
    void expiresOnlyAnOpenHoldButIsAllowedAfterTheDeadline() {
        Authorization hold = hold("100.00");
        assertTrue(hold.hasExpiredBy(NOW.plus(WEEK)));

        // Expiry is the issuer's own release, and the deadline is exactly when it
        // makes sense — unlike a reversal, which the deadline forbids.
        Authorization expired = hold.expire(NOW.plus(WEEK));
        assertEquals(AuthorizationStatus.EXPIRED, expired.status());
        assertEquals(new BigDecimal("100.00"), expired.releasedAmount());

        assertThrows(AuthorizationStateException.class, () -> expired.expire(NOW.plus(WEEK)));
    }

    @Test
    void refusesAHoldThatCannotBeMoney() {
        assertThrows(IllegalArgumentException.class, () -> hold("0.00"));
        assertThrows(IllegalArgumentException.class, () -> hold("-10.00"));
        assertThrows(IllegalArgumentException.class, () -> hold("1.001"));
    }

    private static Authorization hold(String amount) {
        return Authorization.approve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Shopping", new BigDecimal(amount), NOW, WEEK);
    }
}
