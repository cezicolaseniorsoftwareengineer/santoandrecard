package com.cezicola.card.adapter.in.rest;

import jakarta.enterprise.context.RequestScoped;
import jakarta.json.JsonString;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

/**
 * Server-derived caller identity. Tenant and customer are read from the verified
 * access token and never from a request header or body, so a caller cannot choose
 * which tenant or customer it acts as.
 */
@RequestScoped
public class AuthenticatedCaller {
    static final String TENANT_CLAIM = "tenant_id";
    static final String CUSTOMER_CLAIM = "customer_id";

    private final JsonWebToken token;

    public AuthenticatedCaller(JsonWebToken token) {
        this.token = token;
    }

    public UUID tenantId() {
        return requiredUuidClaim(TENANT_CLAIM);
    }

    public UUID customerId() {
        return requiredUuidClaim(CUSTOMER_CLAIM);
    }

    private UUID requiredUuidClaim(String name) {
        Object raw = token.getClaim(name);
        if (raw == null) {
            throw new MissingIdentityClaimException(name);
        }
        String value = raw instanceof JsonString jsonString ? jsonString.getString() : String.valueOf(raw);
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw new MissingIdentityClaimException(name);
        }
    }
}
