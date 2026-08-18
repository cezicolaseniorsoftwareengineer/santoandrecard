package com.cezicola.card.adapter.out.cache;

import com.cezicola.card.application.port.SummaryCache;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * Caches the administrative portfolio summary for a few seconds.
 *
 * <p>That summary aggregates every wallet and every purchase of a tenant, and a
 * dashboard refreshing it repeatedly makes the database do that scan again for
 * an answer that barely moved. The entry always expires: a cached figure with no
 * expiry is a figure that will eventually be wrong with nothing to correct it.
 *
 * <p>A miss and a Redis failure are the same thing to the caller — both mean the
 * ledger is asked directly, which is always allowed to happen.
 */
// Present only when Redis is switched on. Otherwise the default bean in
// NoRedisFallbacks answers instead, with the degraded behaviour stated there.
@ApplicationScoped
@IfBuildProperty(name = "card.redis.enabled", stringValue = "true")
public class RedisSummaryCache implements SummaryCache {
    private static final Logger LOG = Logger.getLogger(RedisSummaryCache.class);

    private final ValueCommands<String, String> values;
    private final Duration ttl;

    public RedisSummaryCache(RedisDataSource redis,
                             @ConfigProperty(name = "card.cache.summary-ttl-seconds") long ttlSeconds) {
        this.values = redis.value(String.class, String.class);
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(values.get(namespaced(key)));
        } catch (RuntimeException unreachable) {
            LOG.warnf(unreachable, "summary cache unavailable, reading through for key %s", key);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value) {
        try {
            values.setex(namespaced(key), ttl.toSeconds(), value);
        } catch (RuntimeException unreachable) {
            // A cache that cannot be written is a slower service, not a broken
            // one. The answer was already computed and is being returned anyway.
            LOG.warnf(unreachable, "could not cache summary for key %s", key);
        }
    }

    private static String namespaced(String key) {
        return "summary:" + key;
    }
}
