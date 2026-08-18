package com.cezicola.card.application.port;

import java.time.Duration;

/**
 * A short-window throttle shared by every replica.
 *
 * <p>Distinct from the durable attempt budget on the card. That budget is the
 * authoritative control and survives restarts; this one exists because a budget
 * of five attempts is no defence if an attacker can spend all five in fifty
 * milliseconds, across replicas, before any of them has written a row.
 */
public interface RateLimiter {

    /**
     * Consumes one permit for the key, returning false when the window is spent.
     *
     * <p>Implementations degrade open: a throttle that cannot be reached must not
     * take the service down with it, because the durable control behind it is
     * still in force.
     */
    boolean tryAcquire(String key, int limit, Duration window);
}
