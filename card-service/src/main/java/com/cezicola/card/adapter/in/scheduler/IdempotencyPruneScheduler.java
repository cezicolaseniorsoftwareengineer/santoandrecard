package com.cezicola.card.adapter.in.scheduler;

import com.cezicola.card.application.IdempotencyRetention;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Runs the retention sweep. The migration promised these records would be pruned
 * by age; this is what makes that true.
 */
@ApplicationScoped
public class IdempotencyPruneScheduler {
    private static final Logger LOG = Logger.getLogger(IdempotencyPruneScheduler.class);

    private final IdempotencyRetention retention;

    public IdempotencyPruneScheduler(IdempotencyRetention retention) {
        this.retention = retention;
    }

    @Scheduled(every = "${card.idempotency.prune-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void prune() {
        try {
            int forgotten = retention.prune();
            if (forgotten > 0) {
                LOG.infof("pruned %d expired idempotency records", forgotten);
            }
        } catch (RuntimeException failure) {
            // Housekeeping that fails is not an incident, but housekeeping that
            // stops silently becomes one.
            LOG.warn("idempotency prune failed and will be retried on the next tick", failure);
        }
    }
}
