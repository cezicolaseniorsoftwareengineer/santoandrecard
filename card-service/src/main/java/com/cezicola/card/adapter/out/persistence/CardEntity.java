package com.cezicola.card.adapter.out.persistence;

import com.cezicola.card.domain.CardProduct;
import com.cezicola.card.domain.CardStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
// The unique constraint is declared here as well as in the migration so the
// schema generated for tests matches the one running in production. Without it
// the suite silently loses the guarantee it is supposed to be checking.
@Table(name = "cards", uniqueConstraints = @UniqueConstraint(
        name = "uq_cards_tenant_idempotency", columnNames = {"tenant_id", "idempotency_key"}))
public class CardEntity {
    @Id
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    public UUID customerId;

    @Column(name = "credit_limit", nullable = false, precision = 19, scale = 2)
    public BigDecimal creditLimit;

    @Column(nullable = false, length = 3)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public CardStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public CardProduct product;

    @Column(name = "last_four_digits", nullable = false, length = 4)
    public String lastFourDigits;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    public String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Version
    public long version;
}
