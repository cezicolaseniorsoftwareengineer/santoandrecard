package com.cezicola.card.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PurchaseBackpressureGuardTest {
    @Test
    void rejectsExcessWorkAndRestoresCapacityAfterCompletion() throws Exception {
        PurchaseBackpressureGuard guard = new PurchaseBackpressureGuard(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> guard.execute(() -> {
                entered.countDown();
                await(release);
                return "completed";
            }));

            entered.await(1, TimeUnit.SECONDS);
            assertThrows(BackpressureRejectedException.class, () -> guard.execute(() -> "rejected"));
            release.countDown();
            assertEquals("completed", first.get(1, TimeUnit.SECONDS));
            assertEquals(1, guard.availablePermits());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while testing backpressure", exception);
        }
    }
}
