package com.cezicola.card.application;

import java.util.UUID;

public final class CardNotFoundException extends RuntimeException {
    public CardNotFoundException(UUID id) {
        super("Card not found: " + id);
    }

    private CardNotFoundException(String message) {
        super(message);
    }

    /**
     * The caller holds no card at all.
     *
     * <p>Named without an identifier because there is none to name: the customer
     * asked to buy on credit and has nothing to buy on.
     */
    public static CardNotFoundException forCustomer() {
        return new CardNotFoundException("the customer holds no card");
    }
}
