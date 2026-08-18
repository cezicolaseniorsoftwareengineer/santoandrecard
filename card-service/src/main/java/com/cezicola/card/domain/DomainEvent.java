package com.cezicola.card.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened, stated as a fact rather than as a command.
 *
 * <p>The payload is already serialised: an event is a record of the past, and
 * re-serialising it later against a changed class would let today's code rewrite
 * yesterday's history.
 */
public record DomainEvent(
        UUID id,
        UUID tenantId,
        UUID aggregateId,
        String type,
        String payload,
        Instant occurredAt) {

    public DomainEvent {
        if (id == null || tenantId == null || aggregateId == null || occurredAt == null) {
            throw new IllegalArgumentException("an event needs an id, a tenant, an aggregate and a time");
        }
        if (type == null || type.isBlank() || type.length() > 64) {
            throw new IllegalArgumentException("event type must contain 1 to 64 characters");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("an event needs a payload");
        }
    }
}
