package com.cezicola.card.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Idempotency must hold when requests overlap, not only when they are retried one
 * after another. Reading for an existing key and then inserting is two steps: two
 * callers can both find nothing and both proceed, so the guarantee has to survive
 * that race rather than assume it away.
 */
@QuarkusTest
class CardIdempotencyConcurrencyTest {
    private static final String TENANT_ID = "99999999-9999-9999-9999-999999999999";
    private static final int CONCURRENT_CALLERS = 8;

    @Test
    @TestSecurity(user = "issuer", roles = "admin")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID)})
    void returnsOneCardWhenTheSameKeyArrivesConcurrently() throws Exception {
        String key = "concurrent-" + UUID.randomUUID();
        String body = "{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":1500.00}";

        List<Response> responses = fireTogether(() -> {
            var http = given().contentType(ContentType.JSON)
                    .header("Idempotency-Key", key).body(body)
                    .when().post("/api/v1/cards").andReturn();
            return new Response(http.statusCode(), http.jsonPath().getString("id"));
        });

        Map<Integer, Long> byStatus = responses.stream()
                .collect(Collectors.groupingBy(Response::status, Collectors.counting()));

        // Every caller must be told the same thing: the one card that exists.
        assertEquals(Map.of(201, (long) CONCURRENT_CALLERS), byStatus,
                "concurrent callers using one key got mixed outcomes: " + byStatus);

        long distinctCards = responses.stream().map(Response::id).distinct().count();
        assertEquals(1, distinctCards, "the same Idempotency-Key produced more than one card");
    }

    @Test
    @TestSecurity(user = "issuer", roles = "admin")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID)})
    void stillRejectsAConflictingPayloadWhenRequestsOverlap() throws Exception {
        String key = "conflict-" + UUID.randomUUID();

        List<Response> responses = fireTogether(() -> {
            // Each caller sends a different credit limit under the same key, so at
            // most one may succeed and the rest must be refused, never silently
            // accepted with someone else's card.
            String body = "{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":"
                    + (100 + Thread.currentThread().getId() % 900) + ".00}";
            var http = given().contentType(ContentType.JSON)
                    .header("Idempotency-Key", key).body(body)
                    .when().post("/api/v1/cards").andReturn();
            return new Response(http.statusCode(), http.jsonPath().getString("id"));
        });

        long created = responses.stream().filter(r -> r.status() == 201).count();
        long refused = responses.stream().filter(r -> r.status() == 409).count();

        assertEquals(1, created, "more than one caller created a card under the same key");
        assertEquals(CONCURRENT_CALLERS - 1, refused,
                "conflicting callers were not all refused with 409: " + responses);
    }

    /** Releases every caller at the same instant so the requests genuinely overlap. */
    private static List<Response> fireTogether(Callable<Response> call) throws Exception {
        var barrier = new CyclicBarrier(CONCURRENT_CALLERS);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS);
        try {
            List<Future<Response>> futures = IntStream.range(0, CONCURRENT_CALLERS)
                    .mapToObj(i -> pool.submit(() -> {
                        barrier.await();
                        return call.call();
                    }))
                    .toList();
            List<Response> responses = new java.util.ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            assertTrue(responses.size() == CONCURRENT_CALLERS);
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Response(int status, String id) {}
}
