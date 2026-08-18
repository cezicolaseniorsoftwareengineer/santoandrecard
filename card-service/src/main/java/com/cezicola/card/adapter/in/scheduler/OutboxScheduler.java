package com.cezicola.card.adapter.in.scheduler;

import com.cezicola.card.application.OutboxRelay;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Wakes the relay. Nothing more: the schedule is a delivery mechanism, so the
 * guarantees stay in {@link OutboxRelay} where they can be tested without one.
 */
@ApplicationScoped
public class OutboxScheduler {
    private static final Logger LOG = Logger.getLogger(OutboxScheduler.class);

    private final OutboxRelay relay;

    public OutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    /**
     * Overlap is skipped rather than queued: a slow broker should leave the
     * backlog for the next tick, not stack up readers competing for the same rows.
     */
    @Scheduled(every = "${card.outbox.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void drain() {
        try {
            int delivered = relay.drain();
            if (delivered > 0) {
                LOG.debugf("published %d outbox events", delivered);
            }
        } catch (RuntimeException failure) {
            // The scheduler must survive a failing tick, or one bad batch ends
            // publication until the service is restarted.
            LOG.warn("outbox drain failed and will be retried on the next tick", failure);
        }
    }
}
