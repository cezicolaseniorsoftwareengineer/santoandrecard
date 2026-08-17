package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.MerchantAuthorizationUnavailableException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class MerchantAuthorizationUnavailableMapper
        implements ExceptionMapper<MerchantAuthorizationUnavailableException> {
    @Override
    public Response toResponse(MerchantAuthorizationUnavailableException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .entity(Map.of("code", "MERCHANT_AUTHORIZATION_UNAVAILABLE", "message", exception.getMessage()))
                .build();
    }
}
