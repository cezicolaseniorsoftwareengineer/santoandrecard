package com.cezicola.card.adapter.out.persistence;

import com.cezicola.card.domain.JournalEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransactionEntity {
    @Id
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public JournalEntry.Kind kind;

    @Column(nullable = false, length = 140)
    public String description;

    @Column(name = "reference_id")
    public UUID referenceId;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;
}
