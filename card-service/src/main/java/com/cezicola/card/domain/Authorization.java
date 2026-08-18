package com.cezicola.card.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A hold placed on a cardholder's funds for a merchant.
 *
 * <p>The rules that decide whether money may move live here rather than in the
 * service, so no future entry point can reach the ledger without passing them.
 * Capture is allowed for at most the amount authorised — a merchant that ships
 * part of an order captures part of the hold — and never after the hold has
 * expired, been reversed, or already been captured.
 */
public record Authorization(
        UUID id,
        UUID tenantId,
        UUID customerId,
        UUID cardId,
        String merchantCategory,
        BigDecimal amount,
        BigDecimal capturedAmount,
        AuthorizationStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant settledAt) {

    public Authorization {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(cardId, "cardId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        requireMoney(amount, "amount");
        if (capturedAmount == null || capturedAmount.signum() < 0) {
            throw new IllegalArgumentException("capturedAmount must not be negative");
        }
        if (capturedAmount.compareTo(amount) > 0) {
            throw new IllegalArgumentException("captured more than was authorised");
        }
        if (merchantCategory == null || merchantCategory.isBlank() || merchantCategory.length() > 64) {
            throw new IllegalArgumentException("merchantCategory must contain 1 to 64 characters");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("a hold must expire after it is placed");
        }
    }

    public static Authorization approve(UUID tenantId, UUID customerId, UUID cardId, String merchantCategory,
                                        BigDecimal amount, Instant now, java.time.Duration holdFor) {
        return new Authorization(UUID.randomUUID(), tenantId, customerId, cardId, merchantCategory, amount,
                BigDecimal.ZERO.setScale(2), AuthorizationStatus.APPROVED, now, now.plus(holdFor), null);
    }

    /**
     * @return this authorization captured, and the amount that has to move
     * @throws AuthorizationStateException when the hold is no longer open, has
     *         expired, or the merchant asks for more than was held
     */
    public Authorization capture(BigDecimal requested, Instant now) {
        requireOpen(now);
        requireMoney(requested, "captured amount");
        if (requested.compareTo(amount) > 0) {
            throw new AuthorizationStateException(
                    "capture of " + requested + " exceeds the authorised " + amount);
        }
        return new Authorization(id, tenantId, customerId, cardId, merchantCategory, amount,
                requested, AuthorizationStatus.CAPTURED, createdAt, expiresAt, now);
    }

    /** Releases the hold. What was held and not captured returns to the wallet. */
    public Authorization reverse(Instant now) {
        requireOpen(now);
        return new Authorization(id, tenantId, customerId, cardId, merchantCategory, amount,
                BigDecimal.ZERO.setScale(2), AuthorizationStatus.REVERSED, createdAt, expiresAt, now);
    }

    /**
     * Expiry is the issuer's own reversal, and unlike one it is allowed after the
     * deadline: that is the only moment it makes sense.
     */
    public Authorization expire(Instant now) {
        if (!status.isOpen()) {
            throw new AuthorizationStateException("authorization is already " + status);
        }
        return new Authorization(id, tenantId, customerId, cardId, merchantCategory, amount,
                BigDecimal.ZERO.setScale(2), AuthorizationStatus.EXPIRED, createdAt, expiresAt, now);
    }

    /** What returns to the wallet when the hold ends: everything not captured. */
    public BigDecimal releasedAmount() {
        return amount.subtract(capturedAmount);
    }

    public boolean hasExpiredBy(Instant now) {
        return status.isOpen() && !now.isBefore(expiresAt);
    }

    private void requireOpen(Instant now) {
        if (!status.isOpen()) {
            throw new AuthorizationStateException("authorization is already " + status);
        }
        if (!now.isBefore(expiresAt)) {
            throw new AuthorizationStateException("the hold expired at " + expiresAt);
        }
    }

    private static void requireMoney(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0 || value.scale() > 2 || value.precision() > 19) {
            throw new IllegalArgumentException(name + " must be positive and fit NUMERIC(19,2)");
        }
    }
}
