package com.cezicola.card.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
class CardResourceTest {
    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
    @Test
    void createsAndReadsCard() {
        String customerId = UUID.randomUUID().toString();
        String cardId = given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", "create-" + UUID.randomUUID())
                .header("X-Tenant-Id", TENANT_ID)
                .body("{\"customerId\":\"" + customerId + "\",\"creditLimit\":1000.00}")
                .when().post("/api/v1/cards")
                .then().statusCode(201)
                .body("customerId", equalTo(customerId))
                .body("currency", equalTo("BRL"))
                .body("status", equalTo("ACTIVE"))
                .body("lastFourDigits", matchesPattern("[0-9]{4}"))
                .extract().path("id");

        given().header("X-Tenant-Id", TENANT_ID).when().get("/api/v1/cards/{id}", cardId)
                .then().statusCode(200)
                .body("id", equalTo(cardId));
    }

    @Test
    void repeatsCreationWithoutDuplicatingCard() {
        String key = "retry-" + UUID.randomUUID();
        String body = "{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":500.00}";
        String firstId = create(key, body);
        String secondId = create(key, body);

        org.junit.jupiter.api.Assertions.assertEquals(firstId, secondId);
    }

    @Test
    void rejectsInvalidLimit() {
        given().contentType(ContentType.JSON)
                .header("X-Tenant-Id", TENANT_ID)
                .header("Idempotency-Key", "invalid-" + UUID.randomUUID())
                .body("{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":0}")
                .when().post("/api/v1/cards")
                .then().statusCode(400);
    }

    @Test
    void rejectsReuseOfIdempotencyKeyWithDifferentPayload() {
        String key = "conflict-" + UUID.randomUUID();
        create(key, "{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":500.00}");

        given().contentType(ContentType.JSON).header("X-Tenant-Id", TENANT_ID).header("Idempotency-Key", key)
                .body("{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":900.00}")
                .when().post("/api/v1/cards")
                .then().statusCode(409).body("code", equalTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void rejectsOversizedIdempotencyKey() {
        given().contentType(ContentType.JSON).header("X-Tenant-Id", TENANT_ID).header("Idempotency-Key", "x".repeat(129))
                .body("{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":100.00}")
                .when().post("/api/v1/cards")
                .then().statusCode(400);
    }

    private static String create(String key, String body) {
        return given().contentType(ContentType.JSON).header("X-Tenant-Id", TENANT_ID).header("Idempotency-Key", key).body(body)
                .when().post("/api/v1/cards")
                .then().statusCode(201).extract().path("id");
    }
}
