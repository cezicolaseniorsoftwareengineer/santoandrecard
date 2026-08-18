package com.cezicola.card.adapter.out.cache;

import com.cezicola.card.application.port.RateLimiter;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * A fixed-window counter in Redis.
 *
 * <p>Redis is the right tool precisely because nothing here is worth keeping:
 * every key expires, and losing the whole dataset costs a window of throttling,
 * not a cent. No money, no identity and no authorisation state lives here — the
 * database holds all of that.
 *
 * <p>The window is anchored by setting the expiry on the first increment only.
 * Refreshing it on every request would let a steady stream of calls hold the key
 * alive forever and never reset the count.
 */
// Present only when Redis is switched on. Otherwise the default bean in
// NoRedisFallbacks answers instead, with the degraded behaviour stated there.
@ApplicationScoped
@IfBuildProperty(name = "card.redis.enabled", stringValue = "true")
public class RedisRateLimiter implements RateLimiter {
    private static final Logger LOG = Logger.getLogger(RedisRateLimiter.class);

    private final ValueCommands<String, Long> counters;
    private final KeyCommands<String> keys;

    public RedisRateLimiter(RedisDataSource redis) {
        this.counters = redis.value(String.class, Long.class);
        this.keys = redis.key(String.class);
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        String namespaced = "ratelimit:" + key;
        try {
            long used = counters.incr(namespaced);
            if (used == 1) {
                // Only the first hit sets the lifetime. Refreshing it on every
                // request would let a steady stream hold the key alive and never
                // reset the count.
                keys.expire(namespaced, window);
            }
            return used <= limit;
        } catch (RuntimeException unreachable) {
            // Degrade open. This throttle sits in front of a durable control that
            // is still enforced; taking payments down because a cache is missing
            // would trade a small risk for a certain outage.
            LOG.warnf(unreachable, "rate limiter unavailable, allowing request for key %s", key);
            return true;
        }
    }
}
