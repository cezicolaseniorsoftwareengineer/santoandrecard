package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.IdempotencyConflictException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class IdempotencyConflictMapper implements ExceptionMapper<IdempotencyConflictException> {
    @Override
    public Response toResponse(IdempotencyConflictException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("code", "IDEMPOTENCY_CONFLICT", "message", exception.getMessage()))
                .build();
    }
}
