package com.cezicola.card.domain;

import java.math.BigDecimal;

/**
 * Raised when a transaction would not balance. This is the ledger's single
 * non-negotiable rule, so it fails the operation rather than recording something
 * that could never be reconciled.
 */
public class UnbalancedTransactionException extends RuntimeException {
    public UnbalancedTransactionException(BigDecimal debits, BigDecimal credits) {
        super("Ledger transaction does not balance: debits=" + debits + " credits=" + credits);
    }

    public UnbalancedTransactionException(String reason) {
        super(reason);
    }
}
