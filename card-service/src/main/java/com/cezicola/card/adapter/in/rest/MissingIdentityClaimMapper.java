package com.cezicola.card.adapter.in.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class MissingIdentityClaimMapper implements ExceptionMapper<MissingIdentityClaimException> {
    @Override
    public Response toResponse(MissingIdentityClaimException exception) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("code", "IDENTITY_CLAIM_MISSING", "claim", exception.claim()))
                .build();
    }
}
