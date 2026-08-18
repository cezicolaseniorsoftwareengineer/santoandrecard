package com.cezicola.card.adapter.in.rest;

import io.quarkus.oidc.OIDCException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * Answers 503 when the identity provider cannot be reached.
 *
 * <p>With the provider down, every protected path returned 500 with a stack
 * trace and an error id. The request was still refused — authentication fails
 * closed, which is the part that matters — but 500 tells a client that its
 * request was wrong, and a client that believes it sent something invalid does
 * not retry. This is the opposite: the request may well be fine and the service
 * cannot tell, which is precisely what 503 means.
 *
 * <p>401 would be wrong for the same reason in reverse. It asserts that the
 * credential failed, and nothing here verified any credential.
 *
 * <p>Authentication runs proactively, in the HTTP layer and before any resource
 * is chosen, so a JAX-RS {@code ExceptionMapper} never sees this failure — the
 * Vert.x failure handler is where it can be caught.
 */
@Singleton
public class IdentityProviderUnavailableHandler {
    private static final Logger LOG = Logger.getLogger(IdentityProviderUnavailableHandler.class);
    private static final int SERVICE_UNAVAILABLE = 503;

    /** Long enough not to amplify the outage, short enough to recover promptly. */
    private static final String RETRY_AFTER_SECONDS = "5";

    void registerOn(@Observes Router router) {
        router.route().failureHandler(this::handle);
    }

    private void handle(RoutingContext context) {
        if (!(rootCauseIsProviderUnavailable(context.failure()))) {
            context.next();
            return;
        }

        // Logged without the stack trace: the provider being unreachable is an
        // operational fact, and one line per request is enough to see it. The
        // stack repeated per request buries everything else in the log.
        LOG.warnf("identity provider unavailable, refusing %s %s",
                context.request().method(), context.normalizedPath());

        if (context.response().ended()) {
            return;
        }
        context.response()
                .setStatusCode(SERVICE_UNAVAILABLE)
                .putHeader("Retry-After", RETRY_AFTER_SECONDS)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                        // No provider URL, no host, no cause: an unauthenticated
                        // caller learns that the service cannot authenticate
                        // right now and nothing about the deployment behind it.
                        .put("code", "IDENTITY_PROVIDER_UNAVAILABLE")
                        .put("message", "Não foi possível verificar sua identidade agora. Tente novamente em instantes.")
                        .encode());
    }

    private static boolean rootCauseIsProviderUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OIDCException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
