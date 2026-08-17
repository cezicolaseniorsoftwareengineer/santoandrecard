package com.cezicola.card.application;

public final class MerchantAuthorizationUnavailableException extends RuntimeException {
    public MerchantAuthorizationUnavailableException() {
        super("Merchant authorization is temporarily unavailable");
    }
}
