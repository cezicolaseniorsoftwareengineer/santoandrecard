package com.cezicola.card.adapter.in.scheduler;

import com.cezicola.card.application.BillingService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Marks statements overdue once their due date has passed with a balance.
 *
 * <p>Outside the request path, like every other sweep here: a customer paying a
 * statement must never wait behind housekeeping over somebody else's.
 *
 * <p>Being late is a state of the claim rather than an event that happens to it,
 * so the sweep only records what is already true. Running it twice changes
 * nothing, and not running it for a day makes a statement late a day later
 * without changing what was owed.
 */
@ApplicationScoped
public class StatementOverdueScheduler {

    private static final Logger LOG = Logger.getLogger(StatementOverdueScheduler.class);

    private final BillingService billing;
    private final int batchSize;

    public StatementOverdueScheduler(
            BillingService billing,
            @org.eclipse.microprofile.config.inject.ConfigProperty(
                    name = "card.statement.overdue-batch-size", defaultValue = "200") int batchSize) {
        this.billing = billing;
        this.batchSize = batchSize;
    }

    @Scheduled(every = "${card.statement.overdue-interval:1h}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        try {
            int marked = billing.markOverdue(batchSize);
            if (marked > 0) {
                LOG.infof("marked %d statement(s) overdue", marked);
            }
        } catch (RuntimeException failure) {
            // A sweep that throws would otherwise be retried on the next tick
            // with no record of why it stopped.
            LOG.error("the overdue sweep failed", failure);
        }
    }
}
