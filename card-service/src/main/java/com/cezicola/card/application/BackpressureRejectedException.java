package com.cezicola.card.application;

public final class BackpressureRejectedException extends RuntimeException {
    public BackpressureRejectedException() {
        super("Purchase capacity is temporarily exhausted");
    }
}
