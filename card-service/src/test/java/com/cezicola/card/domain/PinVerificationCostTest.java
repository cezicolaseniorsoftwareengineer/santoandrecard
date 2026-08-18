package com.cezicola.card.domain;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a PIN verification costs, measured rather than reasoned.
 *
 * <p>ADR-003 accepts a deliberate cost — PBKDF2 at 210,000 iterations — and left
 * the size of it as an open item. This is the part of the reveal path that can be
 * measured honestly without infrastructure: the derivation is pure CPU, it does
 * not touch the database, and it dominates everything else in the request.
 *
 * <p>It is a cost measurement, not a service load test. It says nothing about the
 * HTTP path, connection pool, PostgreSQL or Redis, and it cannot: those numbers
 * require the deployed stack and are still owed. What it does establish is the
 * floor — the reveal endpoint cannot be faster than this, and the concurrent run
 * shows how the cost behaves when every core is busy deriving.
 *
 * <p>Tagged so it is excluded from the normal build: a benchmark that fails a
 * build on a slow agent teaches everyone to ignore it. The single assertion is a
 * sanity bound, orders of magnitude away from the expected value, to catch a
 * derivation that has been accidentally disabled rather than to police latency.
 */
@Tag("benchmark")
class PinVerificationCostTest {
    private static final String PIN = "4731";
    private static final int WARMUP = 20;
    private static final int SAMPLES = 200;

    @Test
    void reportsSerialAndConcurrentVerificationCost() throws Exception {
        CardPin stored = CardPin.of(PIN);

        for (int i = 0; i < WARMUP; i++) {
            stored.matches(PIN);
        }

        long[] serial = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            stored.matches(PIN);
            serial[i] = System.nanoTime() - start;
        }

        int cores = Runtime.getRuntime().availableProcessors();
        long[] concurrent = measureConcurrently(stored, cores);

        System.out.printf("%n=== PIN verification cost (PBKDF2-HMAC-SHA256, 210k iterations) ===%n");
        System.out.printf("cores=%d  samples=%d%n", cores, SAMPLES);
        report("serial     ", serial);
        report("concurrent ", concurrent);
        System.out.printf("throughput ceiling (all cores busy deriving): %.0f verifications/s%n",
                cores * 1_000d / millis(percentile(concurrent, 50)));
        System.out.printf("%nThis is the CPU floor of the reveal endpoint, not a service load test.%n");

        // A derivation that stopped happening would return in microseconds. This
        // bound is far from the expected value on purpose: it catches a broken
        // control, it does not police latency.
        assertTrue(percentile(serial, 50) > 1_000_000L,
                "a verification faster than a millisecond means the derivation is not running");
    }

    private static long[] measureConcurrently(CardPin stored, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<long[]>> work = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                work.add(() -> {
                    long[] samples = new long[SAMPLES / threads + 1];
                    for (int i = 0; i < samples.length; i++) {
                        long start = System.nanoTime();
                        stored.matches(PIN);
                        samples[i] = System.nanoTime() - start;
                    }
                    return samples;
                });
            }
            List<Long> all = new ArrayList<>();
            for (Future<long[]> future : pool.invokeAll(work)) {
                for (long sample : future.get()) {
                    all.add(sample);
                }
            }
            return all.stream().mapToLong(Long::longValue).toArray();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void report(String label, long[] samples) {
        System.out.printf("%s p50=%6.2fms  p95=%6.2fms  p99=%6.2fms  max=%6.2fms%n",
                label, millis(percentile(samples, 50)), millis(percentile(samples, 95)),
                millis(percentile(samples, 99)), millis(percentile(samples, 100)));
    }

    private static long percentile(long[] samples, int percentile) {
        long[] sorted = samples.clone();
        java.util.Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile / 100d * sorted.length) - 1);
        return sorted[Math.max(index, 0)];
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000d;
    }
}
