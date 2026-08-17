package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.BackpressureRejectedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class BackpressureRejectedMapper implements ExceptionMapper<BackpressureRejectedException> {
    @Override
    public Response toResponse(BackpressureRejectedException exception) {
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .entity(Map.of("code", "PURCHASE_BACKPRESSURE", "message", exception.getMessage()))
                .build();
    }
}
