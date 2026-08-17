package com.cezicola.card.application;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@ApplicationScoped
public class PurchaseBackpressureGuard {
    private final Semaphore permits;

    public PurchaseBackpressureGuard(
            @ConfigProperty(name = "card.purchase.max-concurrent", defaultValue = "32") int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        this.permits = new Semaphore(maxConcurrent, true);
    }

    public <T> T execute(Supplier<T> operation) {
        if (!permits.tryAcquire()) {
            throw new BackpressureRejectedException();
        }
        try {
            return operation.get();
        } finally {
            permits.release();
        }
    }

    int availablePermits() {
        return permits.availablePermits();
    }
}
