package com.cezicola.card.application;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException() {
        super("wallet balance is insufficient for this purchase");
    }
}
