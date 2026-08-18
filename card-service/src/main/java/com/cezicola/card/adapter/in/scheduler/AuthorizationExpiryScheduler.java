package com.cezicola.card.adapter.in.scheduler;

import com.cezicola.card.application.AuthorizationService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Releases holds nobody captured.
 *
 * <p>Without this, an abandoned authorization keeps a customer's money out of
 * reach forever. The schedule is a trigger only; what a release means lives in
 * the service, where it can be tested without waiting for a tick.
 */
@ApplicationScoped
public class AuthorizationExpiryScheduler {
    private static final Logger LOG = Logger.getLogger(AuthorizationExpiryScheduler.class);

    private final AuthorizationService authorizations;
    private final int batchSize;

    public AuthorizationExpiryScheduler(
            AuthorizationService authorizations,
            @ConfigProperty(name = "card.authorization.expiry-batch-size") int batchSize) {
        this.authorizations = authorizations;
        this.batchSize = batchSize;
    }

    @Scheduled(every = "${card.authorization.expiry-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void releaseExpiredHolds() {
        try {
            int released = authorizations.expireHolds(batchSize);
            if (released > 0) {
                LOG.infof("released %d expired authorization holds", released);
            }
        } catch (RuntimeException failure) {
            // One bad tick must not end expiry until the service restarts, or
            // customers keep funds held for a reason nobody is watching.
            LOG.warn("authorization expiry sweep failed and will be retried", failure);
        }
    }
}
