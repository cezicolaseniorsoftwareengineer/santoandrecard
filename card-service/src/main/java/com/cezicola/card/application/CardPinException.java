package com.cezicola.card.application;

/** Why a reveal was refused. The reason is coarse on purpose: a caller learns
 *  that it failed and how many attempts remain, never anything about the PIN. */
public class CardPinException extends RuntimeException {
    public enum Reason { NOT_SET, INCORRECT, LOCKED }

    private final Reason reason;
    private final int attemptsRemaining;

    public CardPinException(Reason reason, int attemptsRemaining) {
        super("card PIN rejected: " + reason);
        this.reason = reason;
        this.attemptsRemaining = Math.max(attemptsRemaining, 0);
    }

    public Reason reason() {
        return reason;
    }

    public int attemptsRemaining() {
        return attemptsRemaining;
    }
}
