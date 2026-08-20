package com.cezicola.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a statement, carrying the identity of whatever produced it.
 *
 * <p>{@code sourceType} and {@code sourceId} are unique per tenant, which is what
 * makes closure consume an item exactly once however many times it runs.
 */
@Entity
@Table(name = "statement_items")
public class StatementItemEntity {
    @Id public UUID id;
    @Column(name = "statement_id", nullable = false) public UUID statementId;
    @Column(name = "tenant_id", nullable = false) public UUID tenantId;
    @Column(name = "source_type", nullable = false, length = 24) public String sourceType;
    @Column(name = "source_id", nullable = false) public UUID sourceId;
    @Column(nullable = false, length = 140) public String description;
    @Column(nullable = false, precision = 19, scale = 2) public BigDecimal amount;
    @Column(name = "occurred_at", nullable = false) public Instant occurredAt;
}
