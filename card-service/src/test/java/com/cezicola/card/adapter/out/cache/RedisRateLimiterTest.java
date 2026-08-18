package com.cezicola.card.adapter.out.cache;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The throttle in front of the PIN attempt budget (ADR-003).
 *
 * <p>Redis stands in as an in-memory double rather than a server: what is worth
 * proving here is the counting rule, not that Redis increments. Two properties
 * carry real security weight and neither is visible by reading the call — that
 * the window is anchored on the first hit and never refreshed, and that an
 * unreachable server allows rather than denies.
 *
 * <p>The Redis command interfaces are large and only two methods are used, so the
 * double is a proxy that answers those and rejects anything else. A silent
 * default would let a future call slip past unnoticed.
 */
class RedisRateLimiterTest {
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Test
    void allowsUpToTheLimitAndRefusesTheNextCall() {
        FakeRedis redis = new FakeRedis();
        RedisRateLimiter limiter = new RedisRateLimiter(redis.asDataSource());

        assertTrue(limiter.tryAcquire("pin:card", 3, WINDOW));
        assertTrue(limiter.tryAcquire("pin:card", 3, WINDOW));
        assertTrue(limiter.tryAcquire("pin:card", 3, WINDOW));
        assertFalse(limiter.tryAcquire("pin:card", 3, WINDOW), "the fourth call is over the limit");
    }

    @Test
    void setsTheExpiryOnlyOnTheFirstHitSoTrafficCannotHoldTheWindowOpen() {
        FakeRedis redis = new FakeRedis();
        RedisRateLimiter limiter = new RedisRateLimiter(redis.asDataSource());

        for (int call = 0; call < 5; call++) {
            limiter.tryAcquire("pin:card", 3, WINDOW);
        }

        // Refreshing the lifetime on every request would keep a steadily attacked
        // key alive forever, and the count would never reset.
        assertEquals(List.of(WINDOW), redis.expiries.get("ratelimit:pin:card"));
    }

    @Test
    void countsEachKeySeparately() {
        FakeRedis redis = new FakeRedis();
        RedisRateLimiter limiter = new RedisRateLimiter(redis.asDataSource());

        assertTrue(limiter.tryAcquire("pin:one", 1, WINDOW));
        assertFalse(limiter.tryAcquire("pin:one", 1, WINDOW));
        // One card exhausting its window must not throttle another cardholder.
        assertTrue(limiter.tryAcquire("pin:two", 1, WINDOW));
    }

    @Test
    void namespacesKeysSoTheyCannotCollideWithOtherRedisUsers() {
        FakeRedis redis = new FakeRedis();
        RedisRateLimiter limiter = new RedisRateLimiter(redis.asDataSource());

        limiter.tryAcquire("pin:card", 3, WINDOW);

        assertEquals(1L, redis.counters.get("ratelimit:pin:card"));
    }

    @Test
    void degradesOpenWhenRedisIsUnreachable() {
        FakeRedis redis = new FakeRedis();
        redis.failing = true;
        RedisRateLimiter limiter = new RedisRateLimiter(redis.asDataSource());

        // The durable attempt budget on the card is the authoritative control and
        // is still enforced, so a missing cache must not deny card access.
        assertTrue(limiter.tryAcquire("pin:card", 3, WINDOW));
        assertTrue(limiter.tryAcquire("pin:card", 3, WINDOW));
    }

    /** An in-memory stand-in for the two Redis commands the limiter uses. */
    private static final class FakeRedis {
        private final Map<String, Long> counters = new HashMap<>();
        private final Map<String, List<Duration>> expiries = new HashMap<>();
        private boolean failing;

        RedisDataSource asDataSource() {
            ValueCommands<String, Long> values = proxy(ValueCommands.class, (target, method, args) ->
                    switch (method.getName()) {
                        case "incr" -> incr((String) args[0]);
                        default -> throw unsupported(method.getName());
                    });
            KeyCommands<String> keys = proxy(KeyCommands.class, (target, method, args) -> {
                if (!"expire".equals(method.getName())) {
                    throw unsupported(method.getName());
                }
                return expire((String) args[0], (Duration) args[1]);
            });
            return proxy(RedisDataSource.class, (target, method, args) ->
                    switch (method.getName()) {
                        case "value" -> values;
                        case "key" -> keys;
                        default -> throw unsupported(method.getName());
                    });
        }

        private long incr(String key) {
            failIfUnreachable();
            return counters.merge(key, 1L, Long::sum);
        }

        private boolean expire(String key, Duration window) {
            failIfUnreachable();
            expiries.computeIfAbsent(key, any -> new ArrayList<>()).add(window);
            return true;
        }

        private void failIfUnreachable() {
            if (failing) {
                throw new IllegalStateException("redis is unreachable");
            }
        }

        private static UnsupportedOperationException unsupported(String method) {
            return new UnsupportedOperationException("the limiter is not expected to call " + method);
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        }
    }
}
