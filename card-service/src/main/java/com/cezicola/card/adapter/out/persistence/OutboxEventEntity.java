package com.cezicola.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {
    @Id
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "aggregate_id", nullable = false)
    public UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    public String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String payload;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    /** Null while the event is still owed to the broker. */
    @Column(name = "published_at")
    public Instant publishedAt;

    @Column(nullable = false)
    public int attempts;

    @Column(name = "last_error", length = 500)
    public String lastError;
}
