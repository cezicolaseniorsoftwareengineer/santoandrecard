package com.cezicola.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "wallets", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "customer_id"}))
public class WalletEntity {
    @Id
    @Column(name = "wallet_key", length = 73)
    public String walletKey;
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;
    @Column(name = "customer_id", nullable = false)
    public UUID customerId;
    @Column(nullable = false, precision = 19, scale = 2)
    public BigDecimal balance;
    @Version
    public long version;

    public static String key(UUID tenantId, UUID customerId) {
        return tenantId + ":" + customerId;
    }
}
