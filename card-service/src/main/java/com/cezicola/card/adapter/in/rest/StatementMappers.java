package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.BillingService;
import com.cezicola.card.domain.StatementStateException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * How a statement's refusals reach the caller.
 *
 * <p>The two are different answers to different questions, and collapsing them
 * into one status would tell a client to retry something that can never succeed.
 */
public final class StatementMappers {

    private StatementMappers() {
    }

    /**
     * The statement cannot honour the request in its current state: already
     * closed, already paid, or asked for more than it owes.
     *
     * <p>422 rather than 400: the request was well formed and retrying it
     * unchanged will fail the same way until the statement itself changes.
     */
    @Provider
    public static class IllegalState implements ExceptionMapper<StatementStateException> {
        public Response toResponse(StatementStateException exception) {
            return Response.status(422)
                    .entity(Map.of("code", "STATEMENT_STATE", "message", exception.getMessage()))
                    .build();
        }
    }

    /**
     * No such statement for this caller.
     *
     * <p>The same answer whether it does not exist or belongs to somebody else.
     * Distinguishing them would confirm the existence of another customer's
     * statement to anyone willing to guess identifiers.
     */
    @Provider
    public static class NotFound implements ExceptionMapper<BillingService.StatementNotFoundException> {
        public Response toResponse(BillingService.StatementNotFoundException exception) {
            return Response.status(404)
                    .entity(Map.of("code", "STATEMENT_NOT_FOUND", "message", "statement not found"))
                    .build();
        }
    }
}
