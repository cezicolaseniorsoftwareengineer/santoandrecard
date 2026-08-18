package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.OutboxEventEntity;
import com.cezicola.card.domain.DomainEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.time.Clock;
import java.util.UUID;

/**
 * Records the intent to publish.
 *
 * <p>Deliberately has no transaction of its own: it runs inside the caller's, so
 * the event and the state change it describes commit together or not at all.
 * That is the entire reason the outbox exists — a service that writes to the
 * database and then publishes can lose the event, and one that publishes first
 * can announce work that never happened.
 */
@ApplicationScoped
public class OutboxRecorder {
    private final EntityManager entityManager;
    private final Clock clock;

    @jakarta.inject.Inject
    public OutboxRecorder(EntityManager entityManager) {
        this(entityManager, Clock.systemUTC());
    }

    OutboxRecorder(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public DomainEvent record(UUID tenantId, UUID aggregateId, String type, String payload) {
        DomainEvent event = new DomainEvent(
                UUID.randomUUID(), tenantId, aggregateId, type, payload, clock.instant());

        OutboxEventEntity entity = new OutboxEventEntity();
        entity.id = event.id();
        entity.tenantId = event.tenantId();
        entity.aggregateId = event.aggregateId();
        entity.eventType = event.type();
        entity.payload = event.payload();
        entity.occurredAt = event.occurredAt();
        entity.attempts = 0;
        entityManager.persist(entity);
        return event;
    }
}
