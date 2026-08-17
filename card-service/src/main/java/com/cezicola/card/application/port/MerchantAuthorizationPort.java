package com.cezicola.card.application.port;

import java.math.BigDecimal;
import java.util.UUID;

public interface MerchantAuthorizationPort {
    AuthorizationDecision authorize(UUID tenantId, UUID customerId, String merchantCategory, BigDecimal amount);

    enum AuthorizationDecision {
        APPROVED,
        UNAVAILABLE
    }
}
