package com.cezicola.card.domain;

/**
 * The life of an authorization.
 *
 * <p>An authorization is not a payment. It is a promise that the funds exist and
 * are held for the merchant, and it ends in exactly one of three ways: the
 * merchant takes the money, the merchant gives the hold back, or the hold runs
 * out. Only {@link #APPROVED} can move, which is what stops a capture from being
 * applied twice or after a reversal.
 */
public enum AuthorizationStatus {
    /** Funds are held. The only state from which anything else may happen. */
    APPROVED,
    /** The merchant took the money, in whole or in part. */
    CAPTURED,
    /** The hold was released before capture, by the merchant or the issuer. */
    REVERSED,
    /** The hold ran out before anyone captured it. */
    EXPIRED;

    public boolean isOpen() {
        return this == APPROVED;
    }
}
