package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.IdempotencyRecordEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Forgets idempotency keys once no client could still be retrying with them.
 *
 * <p>The table stores one row per operation that moved money, and without this
 * it grows for the life of the service. Keeping every key forever is not
 * caution: nobody retries a payment a month later, so those rows guard nothing
 * while making each lookup and every backup larger.
 *
 * <p>Retention must outlast the longest retry a client could reasonably make,
 * because deleting early is the dangerous direction — a key forgotten while a
 * caller still holds it turns their retry into a second payment.
 */
@ApplicationScoped
public class IdempotencyRetention {

    private final EntityManager entityManager;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    @jakarta.inject.Inject
    public IdempotencyRetention(EntityManager entityManager,
                                @ConfigProperty(name = "card.idempotency.retention") Duration retention,
                                @ConfigProperty(name = "card.idempotency.prune-batch-size") int batchSize) {
        this(entityManager, Clock.systemUTC(), retention, batchSize);
    }

    IdempotencyRetention(EntityManager entityManager, Clock clock, Duration retention, int batchSize) {
        this.entityManager = entityManager;
        this.clock = clock;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    /**
     * Deletes one bounded batch of expired records.
     *
     * <p>Bounded on purpose. An unbounded delete holds locks for as long as it
     * runs, and a payment queued behind a housekeeping query is a worse outcome
     * than a sweep that takes several passes. The rows are selected first and
     * deleted by key, which keeps the limit meaningful — a bulk delete cannot be
     * limited portably.
     *
     * @return how many records were forgotten
     */
    public int prune() {
        Instant cutoff = clock.instant().minus(retention);
        return QuarkusTransaction.requiringNew().call(() -> {
            List<IdempotencyRecordEntity> expired = entityManager.createQuery("""
                            select r from IdempotencyRecordEntity r
                            where r.createdAt < :cutoff
                            order by r.createdAt asc
                            """, IdempotencyRecordEntity.class)
                    .setParameter("cutoff", cutoff)
                    .setMaxResults(batchSize)
                    .getResultList();

            expired.forEach(entityManager::remove);
            return expired.size();
        });
    }
}
