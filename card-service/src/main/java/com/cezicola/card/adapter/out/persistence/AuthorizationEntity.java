package com.cezicola.card.adapter.out.persistence;

import com.cezicola.card.domain.AuthorizationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "authorizations")
public class AuthorizationEntity {
    @Id
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    public UUID customerId;

    @Column(name = "card_id", nullable = false)
    public UUID cardId;

    @Column(name = "merchant_category", nullable = false, length = 64)
    public String merchantCategory;

    @Column(nullable = false, precision = 19, scale = 2)
    public BigDecimal amount;

    @Column(name = "captured_amount", nullable = false, precision = 19, scale = 2)
    public BigDecimal capturedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public AuthorizationStatus status;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "settled_at")
    public Instant settledAt;

    /**
     * Optimistic locking. A capture and an expiry sweep can reach the same hold
     * at the same moment; the version is what makes one of them lose rather than
     * both succeed.
     */
    @Version
    public long version;
}
