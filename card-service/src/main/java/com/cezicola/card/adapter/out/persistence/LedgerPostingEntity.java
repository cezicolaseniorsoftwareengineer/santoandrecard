package com.cezicola.card.adapter.out.persistence;

import com.cezicola.card.domain.LedgerAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only. There is deliberately no update or delete path: a wrong posting is
 * corrected by a compensating transaction so the history stays auditable.
 */
@Entity
@Table(name = "ledger_postings")
public class LedgerPostingEntity {
    @Id
    public UUID id;

    @Column(name = "transaction_id", nullable = false)
    public UUID transactionId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_code", nullable = false, length = 32)
    public LedgerAccount accountCode;

    @Column(name = "customer_id")
    public UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    public LedgerAccount.Side direction;

    @Column(nullable = false, precision = 19, scale = 2)
    public BigDecimal amount;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;
}
