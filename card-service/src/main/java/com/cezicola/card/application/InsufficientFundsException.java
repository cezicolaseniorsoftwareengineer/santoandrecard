package com.cezicola.card.application;

/**
 * Raised when an account cannot cover a movement.
 *
 * <p>The message names which balance fell short. A customer holding money in the
 * wallet and none on the card is a normal state, and telling them only that
 * "funds are insufficient" while the interface shows a wallet balance is how a
 * refusal reads as a bug.
 */
public class InsufficientFundsException extends RuntimeException {
    private InsufficientFundsException(String message) {
        super(message);
    }

    /** The wallet cannot cover the amount being moved out of it. */
    public static InsufficientFundsException wallet() {
        return new InsufficientFundsException("wallet balance is insufficient for this transfer");
    }

    /** The card cannot cover the purchase. The card is what pays for purchases. */
    public static InsufficientFundsException card() {
        return new InsufficientFundsException("card balance is insufficient for this purchase");
    }

    /**
     * The purchase would take the customer past their credit limit.
     *
     * <p>Distinct from an empty card: nothing is missing from an account, the
     * issuer is simply unwilling to lend more. A customer told they have no
     * balance when they have no *limit* is told the wrong thing.
     */
    public static InsufficientFundsException creditLimit() {
        return new InsufficientFundsException("the purchase exceeds the available credit limit");
    }
}
