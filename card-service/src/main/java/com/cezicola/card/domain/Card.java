package com.cezicola.card.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Card(
        UUID id,
        UUID tenantId,
        UUID customerId,
        BigDecimal creditLimit,
        String currency,
        CardStatus status,
        String lastFourDigits,
        Instant createdAt) {

    public Card {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(creditLimit, "creditLimit must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(lastFourDigits, "lastFourDigits must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (creditLimit.signum() <= 0 || creditLimit.scale() > 2 || creditLimit.precision() > 19) {
            throw new IllegalArgumentException("creditLimit must be positive and fit NUMERIC(19,2)");
        }
        if (!"BRL".equals(currency)) {
            throw new IllegalArgumentException("only BRL is supported in this MVP");
        }
        if (!lastFourDigits.matches("\\d{4}")) {
            throw new IllegalArgumentException("lastFourDigits must contain exactly four digits");
        }
    }
}
