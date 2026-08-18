package com.cezicola.card.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CardTest {
    @Test
    void acceptsValidCard() {
        assertDoesNotThrow(() -> cardWithLimit(new BigDecimal("1000.00")));
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> cardWithLimit(BigDecimal.ZERO));
    }

    @Test
    void rejectsFractionalCents() {
        assertThrows(IllegalArgumentException.class, () -> cardWithLimit(new BigDecimal("1.001")));
    }

    @Test
    void rejectsLimitThatDoesNotFitDatabasePrecision() {
        assertThrows(IllegalArgumentException.class,
                () -> cardWithLimit(new BigDecimal("100000000000000000.00")));
    }

    private static Card cardWithLimit(BigDecimal limit) {
        return new Card(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), limit, "BRL", CardStatus.ACTIVE,
                CardProduct.PLATINUM, CardNumber.generate(), null, 0,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
