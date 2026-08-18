package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.IdempotencyRecordEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Retention has a dangerous direction. Keeping a key too long costs storage;
 * forgetting one while a caller still holds it turns their retry into a second
 * payment, so the boundary is asserted rather than assumed.
 */
@QuarkusTest
class IdempotencyRetentionTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);

    @Inject
    EntityManager entityManager;

    @Test
    void forgetsOnlyWhatIsOlderThanTheRetentionWindow() {
        UUID tenant = UUID.randomUUID();
        record(tenant, "just-inside", NOW.minus(Duration.ofDays(29)));
        record(tenant, "just-outside", NOW.minus(Duration.ofDays(31)));

        assertEquals(1, retention(RETENTION, 500).prune());

        // The key a client could still be retrying with survives.
        assertNotNull(find(tenant, "just-inside"));
        assertNull(find(tenant, "just-outside"));
    }

    @Test
    void deletesInBoundedBatchesSoAPaymentNeverWaitsBehindHousekeeping() {
        UUID tenant = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            record(tenant, "old-" + i, NOW.minus(Duration.ofDays(60)));
        }

        IdempotencyRetention retention = retention(RETENTION, 2);
        assertEquals(2, retention.prune());
        assertEquals(2, retention.prune());
        assertEquals(1, retention.prune());
        // Nothing left to forget, and the sweep says so rather than looping.
        assertEquals(0, retention.prune());
    }

    private IdempotencyRetention retention(Duration window, int batchSize) {
        return new IdempotencyRetention(entityManager, Clock.fixed(NOW, ZoneOffset.UTC), window, batchSize);
    }

    private void record(UUID tenant, String key, Instant createdAt) {
        QuarkusTransaction.requiringNew().run(() -> {
            IdempotencyRecordEntity entity = new IdempotencyRecordEntity();
            entity.tenantId = tenant;
            entity.operation = "wallet-top-up";
            entity.idempotencyKey = key;
            entity.requestDigest = "digest";
            entity.responseStatus = 201;
            entity.responseBody = "{}";
            entity.createdAt = createdAt;
            entityManager.persist(entity);
        });
    }

    private IdempotencyRecordEntity find(UUID tenant, String key) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager.find(IdempotencyRecordEntity.class,
                new IdempotencyRecordEntity.Key(tenant, "wallet-top-up", key)));
    }
}
