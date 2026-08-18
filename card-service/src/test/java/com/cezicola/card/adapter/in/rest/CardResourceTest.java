package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.CardService;
import com.cezicola.card.application.CreateCardCommand;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
class CardResourceTest {
    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CARD_HOLDER = "dddddddd-0000-0000-0000-000000000001";

    @jakarta.inject.Inject
    CardService cardService;

    @Test
    @TestSecurity(user = "issuer", roles = "admin")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID)})
    void createsAndReadsCard() {
        String cardId = given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", "create-" + UUID.randomUUID())
                .body("{\"customerId\":\"" + CARD_HOLDER + "\",\"creditLimit\":1000.00}")
                .when().post("/api/v1/cards")
                .then().statusCode(201)
                .body("customerId", equalTo(CARD_HOLDER))
                .body("currency", equalTo("BRL"))
                .body("status", equalTo("ACTIVE"))
                .body("lastFourDigits", matchesPattern("[0-9]{4}"))
                .extract().path("id");

        given().when().get("/api/v1/cards/{id}", cardId)
                .then().statusCode(200)
                .body("id", equalTo(cardId));
    }

    @Test
    @TestSecurity(user = "issuer", roles = "admin")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID)})
    void repeatsCreationWithoutDuplicatingCard() {
        String key = "retry-" + UUID.randomUUID();
        String body = "{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":500.00}";

        org.junit.jupiter.api.Assertions.assertEquals(create(key, body), create(key, body));
    }

    @Test
    @TestSecurity(user = "issuer", roles = "admin")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID)})
    void rejectsInvalidLimit() {
        given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "invalid-" + UUID.randomUUID())
                .body("{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":0}")
                .when().post("/api/v1/cards")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "issuer", roles = "admin")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID)})
    void rejectsReuseOfIdempotencyKeyWithDifferentPayload() {
        String key = "conflict-" + UUID.randomUUID();
        create(key, "{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":500.00}");

        given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                .body("{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":900.00}")
                .when().post("/api/v1/cards")
                .then().statusCode(409).body("code", equalTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @TestSecurity(user = "issuer", roles = "admin")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID)})
    void rejectsOversizedIdempotencyKey() {
        given().contentType(ContentType.JSON).header("Idempotency-Key", "x".repeat(129))
                .body("{\"customerId\":\"" + UUID.randomUUID() + "\",\"creditLimit\":100.00}")
                .when().post("/api/v1/cards")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "cardholder", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = CARD_HOLDER)})
    void deniesCardIssuanceToCustomers() {
        given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "denied-" + UUID.randomUUID())
                .body("{\"customerId\":\"" + CARD_HOLDER + "\",\"creditLimit\":100.00}")
                .when().post("/api/v1/cards")
                .then().statusCode(403);
    }

    /**
     * A customer must not read another customer's card. The card is created through
     * the service so the request itself can run under the other customer's identity.
     */
    @Test
    @TestSecurity(user = "nosy-cardholder", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "dddddddd-0000-0000-0000-00000000000f")})
    void hidesCardsBelongingToAnotherCustomer() {
        var card = cardService.create(new CreateCardCommand(UUID.fromString(TENANT_ID),
                UUID.fromString(CARD_HOLDER), new java.math.BigDecimal("750.00"),
                com.cezicola.card.domain.CardProduct.PLATINUM, "idor-" + UUID.randomUUID()));

        given().when().get("/api/v1/cards/{id}", card.id().toString())
                .then().statusCode(404).body("code", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    void rejectsUnauthenticatedCardCreation() {
        given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "anon-" + UUID.randomUUID())
                .body("{\"customerId\":\"" + CARD_HOLDER + "\",\"creditLimit\":100.00}")
                .when().post("/api/v1/cards")
                .then().statusCode(401);
    }

    private static String create(String key, String body) {
        return given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(body)
                .when().post("/api/v1/cards")
                .then().statusCode(201).extract().path("id");
    }
}
