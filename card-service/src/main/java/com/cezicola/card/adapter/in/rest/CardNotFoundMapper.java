package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.CardNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class CardNotFoundMapper implements ExceptionMapper<CardNotFoundException> {
    @Override
    public Response toResponse(CardNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("code", "CARD_NOT_FOUND", "message", exception.getMessage()))
                .build();
    }
}
