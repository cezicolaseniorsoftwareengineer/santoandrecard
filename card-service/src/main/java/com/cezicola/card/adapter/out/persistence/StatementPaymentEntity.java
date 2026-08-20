package com.cezicola.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "statement_payments")
public class StatementPaymentEntity {
    @Id public UUID id;
    @Column(name = "statement_id", nullable = false) public UUID statementId;
    @Column(name = "tenant_id", nullable = false) public UUID tenantId;
    @Column(nullable = false, precision = 19, scale = 2) public BigDecimal amount;
    @Column(name = "paid_at", nullable = false) public Instant paidAt;
    /** The entry that moved the money. A payment without one reduces a claim for free. */
    @Column(name = "ledger_transaction_id", nullable = false) public UUID ledgerTransactionId;
}
