package com.cezicola.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The stored outcome of an operation that moved money.
 *
 * <p>The composite key is the guarantee. Two concurrent replays both find
 * nothing and both proceed; the primary key is what stops the second from
 * committing, exactly as the unique index does for card issuance.
 */
@Entity
@Table(name = "idempotency_records")
@IdClass(IdempotencyRecordEntity.Key.class)
public class IdempotencyRecordEntity {

    @Id
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Id
    @Column(nullable = false, length = 32)
    public String operation;

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128)
    public String idempotencyKey;

    @Column(name = "request_digest", nullable = false, length = 64)
    public String requestDigest;

    @Column(name = "response_status", nullable = false)
    public int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    public String responseBody;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static class Key implements Serializable {
        public UUID tenantId;
        public String operation;
        public String idempotencyKey;

        public Key() {
        }

        public Key(UUID tenantId, String operation, String idempotencyKey) {
            this.tenantId = tenantId;
            this.operation = operation;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(tenantId, key.tenantId)
                    && Objects.equals(operation, key.operation)
                    && Objects.equals(idempotencyKey, key.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, operation, idempotencyKey);
        }
    }
}
