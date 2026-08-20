package com.cezicola.card.adapter.out.persistence;

import com.cezicola.card.domain.StatementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "statements")
public class StatementEntity {
    @Id public UUID id;
    @Column(name = "tenant_id", nullable = false) public UUID tenantId;
    @Column(name = "customer_id", nullable = false) public UUID customerId;
    /** `2026-08`, sortable as text so ordering cycles needs no date arithmetic. */
    @Column(nullable = false, length = 7) public String cycle;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) public StatementStatus status;
    @Column(name = "billed_total", nullable = false, precision = 19, scale = 2) public BigDecimal billedTotal;
    @Column(name = "paid_total", nullable = false, precision = 19, scale = 2) public BigDecimal paidTotal;
    @Column(name = "due_date") public LocalDate dueDate;
    @Column(name = "closed_at") public Instant closedAt;
    @Column(name = "created_at", nullable = false) public Instant createdAt;

    /**
     * Two payments arriving at once would otherwise both read the same balance
     * and both be allowed, settling more than was owed. The row is also locked
     * where it matters; this catches the case a lock was not taken.
     */
    @Version
    @Column(name = "version") public Long version;
}
