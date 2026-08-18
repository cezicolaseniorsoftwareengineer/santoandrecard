package com.cezicola.card.adapter.out.cache;

import com.cezicola.card.application.port.RateLimiter;
import com.cezicola.card.application.port.SummaryCache;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Duration;
import java.util.Optional;

/**
 * What the service does without Redis.
 *
 * <p>Both behaviours are the degraded ones on purpose, and they are the same
 * behaviours the Redis adapters fall back to when the server is unreachable: the
 * throttle allows, and the cache always misses. Neither weakens a guarantee,
 * because neither holds one — the durable attempt budget still locks the card
 * and the ledger still answers every read.
 *
 * <p>Declared as default beans, so a deployment with Redis switched off is a
 * configuration choice rather than a wiring failure, and the fast test profile
 * runs with no server to reach.
 */
@ApplicationScoped
public class NoRedisFallbacks {

    @Produces
    @DefaultBean
    @ApplicationScoped
    public RateLimiter permissiveRateLimiter() {
        return (key, limit, window) -> true;
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public SummaryCache alwaysMissingCache() {
        return new SummaryCache() {
            @Override
            public Optional<String> get(String key) {
                return Optional.empty();
            }

            @Override
            public void put(String key, String value) {
                // Nothing to keep it in.
            }
        };
    }
}
