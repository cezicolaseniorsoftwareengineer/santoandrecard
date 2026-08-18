package com.cezicola.card.application;

import java.util.UUID;

public class AuthorizationNotFoundException extends RuntimeException {
    public AuthorizationNotFoundException(UUID id) {
        super("authorization " + id + " was not found");
    }
}
