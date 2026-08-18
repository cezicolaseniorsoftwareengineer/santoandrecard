package com.cezicola.card.adapter.out.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/**
 * What an operator needs to see at three in the morning.
 *
 * <p>Runtime metrics say the process is alive; they do not say the business is.
 * A service can answer every request in ten milliseconds while every
 * authorization is being declined, and the JVM gauges will look perfect
 * throughout. These meters describe outcomes instead: what was held, what was
 * captured, what was refused and why.
 *
 * <p>Amounts are counted in centavos as whole numbers. Micrometer carries
 * doubles, and money does not survive that — a counter incremented by 0.1 a
 * thousand times does not read 100.
 */
@ApplicationScoped
public class FinancialMetrics {

    private final MeterRegistry registry;

    public FinancialMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One authorization decision, with the reason it went that way. */
    public void authorizationDecided(String outcome) {
        Counter.builder("card.authorization.decisions")
                .description("Authorization decisions by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void authorizationHeld(BigDecimal amount) {
        moved("card.authorization.held.centavos", "Amount placed on hold", amount);
    }

    public void authorizationSettled(String outcome, BigDecimal captured, BigDecimal released) {
        Counter.builder("card.authorization.settlements")
                .description("Authorizations by how they ended")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        if (captured.signum() > 0) {
            moved("card.authorization.captured.centavos", "Amount captured by merchants", captured);
        }
        if (released.signum() > 0) {
            moved("card.authorization.released.centavos", "Amount returned to wallets", released);
        }
    }

    public void moneyMoved(String operation, BigDecimal amount) {
        Counter.builder("card.money.moved.centavos")
                .description("Money moved by operation")
                .tag("operation", operation)
                .register(registry)
                .increment(centavos(amount));
    }

    /** A refusal is an outcome worth counting; a spike in one reason is an incident. */
    public void refused(String operation, String reason) {
        Counter.builder("card.operations.refused")
                .description("Operations refused, by reason")
                .tags("operation", operation, "reason", reason)
                .register(registry)
                .increment();
    }

    public void pinVerification(String outcome) {
        Counter.builder("card.pin.verifications")
                .description("PIN verification attempts by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    /**
     * A generated card number that was already taken.
     *
     * <p>Expected to stay at zero. A rising count means the number space is
     * smaller than it looks or generation is not as random as assumed, and both
     * are worth knowing before a customer is issued a card that collides.
     */
    public void cardNumberCollision() {
        Counter.builder("card.number.collisions")
                .description("Generated card numbers that were already issued")
                .register(registry)
                .increment();
    }

    /** How long money sits owed to the broker before an event actually leaves. */
    public void outboxDrained(int delivered, long nanos) {
        Timer.builder("card.outbox.drain")
                .description("Time to drain one outbox batch")
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
        if (delivered > 0) {
            Counter.builder("card.outbox.published")
                    .description("Events handed to the broker")
                    .register(registry)
                    .increment(delivered);
        }
    }

    public void outboxDeliveryFailed() {
        Counter.builder("card.outbox.delivery.failures")
                .description("Events the broker refused")
                .register(registry)
                .increment();
    }

    private void moved(String name, String description, BigDecimal amount) {
        Counter.builder(name).description(description).register(registry).increment(centavos(amount));
    }

    private static double centavos(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }
}
