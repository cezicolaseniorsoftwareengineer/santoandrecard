package com.cezicola.card.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A client that times out cannot tell a lost request from a lost response. For a
 * payment those are opposite situations, and only the server knows which
 * happened — so a replayed key must return the first outcome rather than move
 * money again.
 */
@QuarkusTest
class MoneyIdempotencyTest {
    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "bbbbbbb1-0000-0000-0000-000000000001")})
    void replaysATopUpInsteadOfRepeatingIt() {
        String key = "top-up-" + UUID.randomUUID();

        float first = topUp(key, "100.00").extract().path("balance");
        float again = topUp(key, "100.00").extract().path("balance");

        assertEquals(first, again, "the replay reported a different balance");
        given().when().get("/api/v1/wallet")
                .then().body("balance", equalTo(100.00f));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "bbbbbbb1-0000-0000-0000-000000000002")})
    void refusesAKeyReplayedWithADifferentAmount() {
        String key = "top-up-" + UUID.randomUUID();
        topUp(key, "50.00");

        // Same key, different intent. Answering with the first outcome would tell
        // the caller a request it never made had succeeded.
        given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                .body(amount("999.00"))
                .when().post("/api/v1/wallet/top-ups")
                .then().statusCode(409).body("code", equalTo("IDEMPOTENCY_CONFLICT"));

        given().when().get("/api/v1/wallet").then().body("balance", equalTo(50.00f));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "bbbbbbb1-0000-0000-0000-000000000003")})
    void movesMoneyOnceWhenRetriesOverlap() throws Exception {
        String key = "top-up-" + UUID.randomUUID();
        Callable<Float> call = () -> topUp(key, "200.00").extract().path("balance");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            pool.invokeAll(List.of(call, call, call, call)).forEach(future -> {
                try {
                    future.get();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        } finally {
            pool.shutdownNow();
        }

        // Four overlapping calls, one credit. Checking and then inserting cannot
        // guarantee this on its own; the primary key is what does.
        given().when().get("/api/v1/wallet").then().body("balance", equalTo(200.00f));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "bbbbbbb1-0000-0000-0000-000000000004")})
    void appliesTheSameGuaranteeToCardLoadsAndPurchases() {
        topUp("seed-" + UUID.randomUUID(), "1000.00");

        String loadKey = "load-" + UUID.randomUUID();
        load(loadKey, "300.00");
        load(loadKey, "300.00");

        String purchaseKey = "purchase-" + UUID.randomUUID();
        purchase(purchaseKey);
        purchase(purchaseKey);

        // One load and one purchase, whatever the retries did: 300.00 reached the
        // card and a single 90.00 purchase was taken from it.
        given().when().get("/api/v1/wallet").then().body("cardBalance", equalTo(210.00f));
        given().when().get("/api/v1/purchases").then().body("size()", equalTo(1));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "bbbbbbb1-0000-0000-0000-000000000005")})
    void refusesToMoveMoneyWithoutAKey() {
        given().contentType(ContentType.JSON).body(amount("10.00"))
                .when().post("/api/v1/wallet/top-ups")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "bbbbbbb1-0000-0000-0000-000000000006")})
    void scopesTheKeyToItsOperation() {
        String key = "shared-" + UUID.randomUUID();
        topUp(key, "500.00");

        // The same key on a different operation describes a different intent, so
        // it must run rather than replay the top-up's response.
        load(key, "100.00");

        given().when().get("/api/v1/wallet")
                .then().body("balance", equalTo(400.00f)).body("cardBalance", equalTo(100.00f));
    }

    private static io.restassured.response.ValidatableResponse topUp(String key, String value) {
        return given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(amount(value))
                .when().post("/api/v1/wallet/top-ups").then().statusCode(201);
    }

    private static void load(String key, String value) {
        given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(amount(value))
                .when().post("/api/v1/wallet/card-loads").then().statusCode(201);
    }

    private static void purchase(String key) {
        given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                .body("{\"merchantCategory\":\"Padaria\",\"amount\":90.00,\"installments\":1}")
                .when().post("/api/v1/purchases").then().statusCode(201);
    }

    private static String amount(String value) {
        return "{\"amount\":" + value + "}";
    }
}
