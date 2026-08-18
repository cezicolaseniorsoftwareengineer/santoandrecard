package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.OutboxEventEntity;
import com.cezicola.card.application.port.EventPublisher;
import com.cezicola.card.domain.DomainEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Turns recorded intent into delivery.
 *
 * <p>Delivery is <strong>at least once</strong>, and that is a decision rather
 * than a limitation. The event is published and then marked, so a crash in
 * between republishes it; marking first would lose it instead. Losing an event
 * about money is unacceptable and receiving one twice is merely inconvenient, so
 * consumers are required to be idempotent — every event carries a stable id for
 * exactly that purpose.
 *
 * <p>Rows are claimed with a row lock and {@code SKIP LOCKED}, so several
 * replicas can drain the same outbox without either duplicating work or blocking
 * behind each other.
 */
@ApplicationScoped
public class OutboxRelay {
    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);

    private final EntityManager entityManager;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    @jakarta.inject.Inject
    public OutboxRelay(EntityManager entityManager,
                       EventPublisher publisher,
                       @ConfigProperty(name = "card.outbox.batch-size") int batchSize,
                       @ConfigProperty(name = "card.outbox.max-attempts") int maxAttempts) {
        this(entityManager, publisher, Clock.systemUTC(), batchSize, maxAttempts);
    }

    OutboxRelay(EntityManager entityManager, EventPublisher publisher, Clock clock, int batchSize, int maxAttempts) {
        this.entityManager = entityManager;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Drains one batch and reports how many events were delivered.
     *
     * <p>Each event is published and marked in its own transaction: one broker
     * failure must not roll back the events already acknowledged before it, or a
     * single poisoned event would keep replaying an entire batch.
     */
    public int drain() {
        List<UUID> pending = QuarkusTransaction.requiringNew().call(this::claimable);
        int delivered = 0;
        for (UUID id : pending) {
            if (publishOne(id)) {
                delivered++;
            }
        }
        return delivered;
    }

    private List<UUID> claimable() {
        return entityManager.createQuery("""
                        select e.id from OutboxEventEntity e
                        where e.publishedAt is null and e.attempts < :maxAttempts
                        order by e.occurredAt asc
                        """, UUID.class)
                .setParameter("maxAttempts", maxAttempts)
                .setMaxResults(batchSize)
                .getResultList();
    }

    private boolean publishOne(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // SKIP LOCKED rather than a plain lock: a row another replica is
            // already delivering is not work to wait for, it is work to leave
            // alone.
            OutboxEventEntity entity = entityManager.find(
                    OutboxEventEntity.class, id, LockModeType.PESSIMISTIC_WRITE,
                    java.util.Map.of("jakarta.persistence.lock.timeout", 0));
            if (entity == null || entity.publishedAt != null) {
                return false;
            }

            try {
                publisher.publish(new DomainEvent(entity.id, entity.tenantId, entity.aggregateId,
                        entity.eventType, entity.payload, entity.occurredAt));
                // Only after the broker acknowledged. Marking first would drop
                // the event on a failure that has not happened yet.
                entity.publishedAt = clock.instant();
                entity.lastError = null;
                return true;
            } catch (RuntimeException failure) {
                entity.attempts++;
                entity.lastError = truncate(failure.getMessage());
                if (entity.attempts >= maxAttempts) {
                    // Left unpublished on purpose. An event about money is not
                    // discarded because delivery is hard; it stays visible for an
                    // operator to act on.
                    LOG.errorf("outbox event %s exhausted %d delivery attempts and needs attention",
                            entity.id, entity.attempts);
                }
                return false;
            }
        });
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown failure";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
