package com.cezicola.card.application;

import java.util.UUID;

public final class CardNotFoundException extends RuntimeException {
    public CardNotFoundException(UUID id) {
        super("Card not found: " + id);
    }
}
