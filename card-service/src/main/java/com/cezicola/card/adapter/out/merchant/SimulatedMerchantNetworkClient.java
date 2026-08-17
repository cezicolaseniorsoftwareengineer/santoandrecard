package com.cezicola.card.adapter.out.merchant;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.UUID;

@ApplicationScoped
public class SimulatedMerchantNetworkClient {
    public void authorize(UUID tenantId, UUID customerId, String merchantCategory, BigDecimal amount) {
        if ("NETWORK_FAILURE".equalsIgnoreCase(merchantCategory)) {
            throw new MerchantNetworkException("Simulated merchant network failure");
        }
    }

    public static final class MerchantNetworkException extends RuntimeException {
        public MerchantNetworkException(String message) {
            super(message);
        }
    }
}
