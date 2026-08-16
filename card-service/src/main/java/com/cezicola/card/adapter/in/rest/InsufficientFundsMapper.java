package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.InsufficientFundsException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class InsufficientFundsMapper implements ExceptionMapper<InsufficientFundsException> {
    public Response toResponse(InsufficientFundsException exception) {
        return Response.status(422).entity(Map.of("code", "INSUFFICIENT_FUNDS", "message", exception.getMessage())).build();
    }
}
