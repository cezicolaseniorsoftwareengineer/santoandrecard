package com.cezicola.card.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A card as the issuer holds it. The PIN is null until the cardholder sets one,
 * and while it is null the number cannot be revealed: there is nothing to check
 * a request against.
 */
public record Card(
        UUID id,
        UUID tenantId,
        UUID customerId,
        BigDecimal creditLimit,
        String currency,
        CardStatus status,
        CardProduct product,
        CardNumber number,
        CardPin pin,
        int pinAttempts,
        Instant createdAt) {

    public Card {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(creditLimit, "creditLimit must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(number, "number must not be null");
        if (pinAttempts < 0) {
            throw new IllegalArgumentException("pinAttempts cannot be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (creditLimit.signum() <= 0 || creditLimit.scale() > 2 || creditLimit.precision() > 19) {
            throw new IllegalArgumentException("creditLimit must be positive and fit NUMERIC(19,2)");
        }
        if (!"BRL".equals(currency)) {
            throw new IllegalArgumentException("only BRL is supported in this MVP");
        }
    }

    /** Derived from the number rather than stored beside it, so the two cannot disagree. */
    public String lastFourDigits() {
        return number.lastFourDigits();
    }

    /** The number stays hidden until there is a PIN to check a reveal against. */
    public boolean hasPin() {
        return pin != null;
    }
}
