package com.cezicola.card.adapter.out.merchant;

import com.cezicola.card.application.port.MerchantAuthorizationPort;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Timeout;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@ApplicationScoped
public class ResilientMerchantAuthorizationGateway implements MerchantAuthorizationPort {
    private final SimulatedMerchantNetworkClient client;

    public ResilientMerchantAuthorizationGateway(SimulatedMerchantNetworkClient client) {
        this.client = client;
    }

    @Override
    @Timeout(value = 500, unit = ChronoUnit.MILLIS)
    @CircuitBreaker(
            requestVolumeThreshold = 4,
            failureRatio = 0.5,
            delay = 5,
            delayUnit = ChronoUnit.SECONDS,
            successThreshold = 2)
    @Fallback(fallbackMethod = "declineWhenUnavailable")
    public AuthorizationDecision authorize(
            UUID tenantId, UUID customerId, String merchantCategory, BigDecimal amount) {
        client.authorize(tenantId, customerId, merchantCategory, amount);
        return AuthorizationDecision.APPROVED;
    }

    AuthorizationDecision declineWhenUnavailable(
            UUID tenantId, UUID customerId, String merchantCategory, BigDecimal amount) {
        return AuthorizationDecision.UNAVAILABLE;
    }
}
