package com.cezicola.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "interest_policies")
public class InterestPolicyEntity {
    @Id @Column(name = "tenant_id") public UUID tenantId;
    @Column(name = "monthly_rate", nullable = false, precision = 9, scale = 6) public BigDecimal monthlyRate;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
