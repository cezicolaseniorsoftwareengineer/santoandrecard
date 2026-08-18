package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.OutboxEventEntity;
import com.cezicola.card.application.port.EventPublisher;
import com.cezicola.card.domain.DomainEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbox exists for two properties, and both are tested without a broker:
 * an event is never published for work that rolled back, and an event is never
 * marked delivered unless the publisher acknowledged it.
 */
@QuarkusTest
class OutboxRelayTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Inject
    EntityManager entityManager;

    @Inject
    OutboxRecorder recorder;

    @Inject
    FinanceService finance;

    @Inject
    com.cezicola.card.adapter.out.metrics.FinancialMetrics metrics;

    @Test
    void writesTheEventInTheSameTransactionAsTheMoney() {
        UUID customer = UUID.randomUUID();
        finance.topUp(TENANT, customer, new BigDecimal("120.00"));

        List<OutboxEventEntity> events = eventsFor(customer);
        assertEquals(1, events.size());
        assertEquals("wallet.topped-up", events.get(0).eventType);
        assertNull(events.get(0).publishedAt, "a recorded event is not a delivered one");
        assertTrue(events.get(0).payload.contains("120.00"));
    }

    @Test
    void keepsNoEventWhenTheWorkRollsBack() {
        UUID customer = UUID.randomUUID();

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                recorder.record(TENANT, customer, "wallet.topped-up", "{}");
                // Whatever the operation was, it failed after recording intent.
                throw new IllegalStateException("the operation failed");
            });
        } catch (IllegalStateException expected) {
            // The rollback is the point of the test.
        }

        assertEquals(0, eventsFor(customer).size(), "a rolled back operation announced itself anyway");
    }

    @Test
    void marksAnEventPublishedOnlyAfterThePublisherAcceptsIt() {
        UUID customer = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> recorder.record(TENANT, customer, "card.loaded", "{}"));

        RecordingPublisher publisher = new RecordingPublisher();
        OutboxRelay relay = new OutboxRelay(entityManager, publisher, metrics, java.time.Clock.systemUTC(), 100, 10);

        relay.drain();
        assertNotNull(eventsFor(customer).get(0).publishedAt);
        long deliveredForCustomer = publisher.countFor(customer);
        assertEquals(1, deliveredForCustomer);

        // Draining again must not republish what was already delivered.
        relay.drain();
        assertEquals(1, publisher.countFor(customer), "a delivered event was published twice");
    }

    @Test
    void leavesTheEventOwedWhenTheBrokerRefusesIt() {
        UUID customer = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> recorder.record(TENANT, customer, "card.loaded", "{}"));

        FailingPublisher publisher = new FailingPublisher();
        OutboxRelay relay = new OutboxRelay(entityManager, publisher, metrics, java.time.Clock.systemUTC(), 100, 10);

        assertEquals(0, relay.drain());

        OutboxEventEntity event = eventsFor(customer).get(0);
        assertNull(event.publishedAt, "an event the broker refused was marked delivered");
        assertEquals(1, event.attempts);
        assertNotNull(event.lastError);

        // Still owed: a broker that recovers gets the event it missed, which is
        // what makes the delivery at-least-once rather than best-effort. The
        // batch count is not asserted — the outbox is shared, so other events
        // recorded by this class ride along in the same drain.
        OutboxRelay recovered =
                new OutboxRelay(entityManager, new RecordingPublisher(), metrics, java.time.Clock.systemUTC(), 100, 10);
        recovered.drain();
        assertNotNull(eventsFor(customer).get(0).publishedAt, "the missed event was never redelivered");
    }

    private List<OutboxEventEntity> eventsFor(UUID customer) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager
                .createQuery("select e from OutboxEventEntity e where e.aggregateId = :id order by e.occurredAt",
                        OutboxEventEntity.class)
                .setParameter("id", customer)
                .getResultList());
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<DomainEvent> delivered = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            delivered.add(event);
        }

        long countFor(UUID aggregateId) {
            return delivered.stream().filter(event -> event.aggregateId().equals(aggregateId)).count();
        }
    }

    private static final class FailingPublisher implements EventPublisher {
        @Override
        public void publish(DomainEvent event) {
            throw new IllegalStateException("broker unavailable");
        }
    }
}
