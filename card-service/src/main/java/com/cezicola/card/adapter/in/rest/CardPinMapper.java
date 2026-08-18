package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.CardPinException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * A rejected PIN answers 403 rather than 401: the caller is authenticated and
 * owns the card, it simply failed a second factor. 423 marks the locked card,
 * which is a different situation for the interface — retrying will not help.
 *
 * <p>The body says how many attempts remain so the cardholder is not locked out
 * by surprise. It says nothing about the PIN itself.
 */
@Provider
public class CardPinMapper implements ExceptionMapper<CardPinException> {
    @Override
    public Response toResponse(CardPinException exception) {
        // 423 Locked is not part of the JAX-RS status enum, so the code is used
        // directly. Looking it up returned null and turned every lock into a 500.
        int status = switch (exception.reason()) {
            case LOCKED -> 423;
            case NOT_SET -> Response.Status.CONFLICT.getStatusCode();
            case INCORRECT -> Response.Status.FORBIDDEN.getStatusCode();
        };
        return Response.status(status)
                .entity(Map.of(
                        "code", "CARD_PIN_" + exception.reason(),
                        "message", messageFor(exception),
                        "attemptsRemaining", exception.attemptsRemaining()))
                .build();
    }

    private static String messageFor(CardPinException exception) {
        return switch (exception.reason()) {
            case LOCKED -> "Cartão bloqueado por excesso de tentativas. Defina um novo PIN.";
            case NOT_SET -> "Defina um PIN antes de revelar o número.";
            case INCORRECT -> "PIN incorreto. Tentativas restantes: " + exception.attemptsRemaining() + ".";
        };
    }
}
