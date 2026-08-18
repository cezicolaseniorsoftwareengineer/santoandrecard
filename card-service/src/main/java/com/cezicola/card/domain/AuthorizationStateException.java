package com.cezicola.card.domain;

/** Raised when an authorization is asked to do something its state forbids. */
public class AuthorizationStateException extends RuntimeException {
    public AuthorizationStateException(String message) {
        super(message);
    }
}
