package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.AuthorizationNotFoundException;
import com.cezicola.card.domain.AuthorizationStateException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * A hold that cannot do what was asked answers 409, not 400: the request was
 * well formed and the caller was allowed to make it — the authorization had
 * simply already been settled, or the deadline had passed. Retrying the same
 * call will not change that, and the status says so.
 */
public final class AuthorizationMappers {

    @Provider
    public static class NotFound implements ExceptionMapper<AuthorizationNotFoundException> {
        @Override
        public Response toResponse(AuthorizationNotFoundException exception) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("code", "AUTHORIZATION_NOT_FOUND", "message", exception.getMessage()))
                    .build();
        }
    }

    @Provider
    public static class IllegalState implements ExceptionMapper<AuthorizationStateException> {
        @Override
        public Response toResponse(AuthorizationStateException exception) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("code", "AUTHORIZATION_STATE", "message", exception.getMessage()))
                    .build();
        }
    }

    private AuthorizationMappers() {
    }
}
