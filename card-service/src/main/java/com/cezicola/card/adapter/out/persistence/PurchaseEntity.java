package com.cezicola.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "purchases")
public class PurchaseEntity {
    @Id public UUID id;
    @Column(name = "tenant_id", nullable = false) public UUID tenantId;
    @Column(name = "customer_id", nullable = false) public UUID customerId;
    @Column(name = "merchant_category", nullable = false, length = 64) public String merchantCategory;
    @Column(nullable = false, precision = 19, scale = 2) public BigDecimal principal;
    @Column(nullable = false, precision = 19, scale = 2) public BigDecimal interest;
    @Column(nullable = false, precision = 19, scale = 2) public BigDecimal total;
    @Column(nullable = false) public int installments;
    @Column(name = "installment_amount", nullable = false, precision = 19, scale = 2) public BigDecimal installmentAmount;
    /** Carries the rounding remainder, so the instalments add up to the total. */
    @Column(name = "last_installment_amount", nullable = false, precision = 19, scale = 2) public BigDecimal lastInstallmentAmount;
    /** The administered rate this purchase was priced under, kept for audit. */
    @Column(name = "monthly_rate", nullable = false, precision = 9, scale = 6) public BigDecimal monthlyRate;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
