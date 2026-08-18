package com.cezicola.card.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

/**
 * The number is guarded by the PIN, and the PIN is guarded by a budget. Four
 * digits is ten thousand possibilities, so an attacker who may guess without
 * limit will get in; what has to hold is that the budget runs out first.
 */
@QuarkusTest
class CardPinRevealTest {
    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "fffffff1-0000-0000-0000-000000000001")})
    void revealsTheNumberOnlyAfterTheRightPin() {
        String cardId = issueCard();

        // Before a PIN exists there is nothing to check a reveal against.
        given().contentType(ContentType.JSON).body(pin("1234"))
                .when().post("/api/v1/cards/{id}/number", cardId)
                .then().statusCode(409).body("code", equalTo("CARD_PIN_NOT_SET"));

        given().contentType(ContentType.JSON).body(pin("4821"))
                .when().put("/api/v1/cards/{id}/pin", cardId)
                .then().statusCode(200).body("pinDefined", equalTo(true));

        given().contentType(ContentType.JSON).body(pin("0000"))
                .when().post("/api/v1/cards/{id}/number", cardId)
                .then().statusCode(403)
                .body("code", equalTo("CARD_PIN_INCORRECT"))
                .body("attemptsRemaining", equalTo(4));

        given().contentType(ContentType.JSON).body(pin("4821"))
                .when().post("/api/v1/cards/{id}/number", cardId)
                .then().statusCode(200)
                .body("number", matchesPattern("[0-9]{16}"))
                .body("formatted", matchesPattern("[0-9]{4} [0-9]{4} [0-9]{4} [0-9]{4}"));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "fffffff1-0000-0000-0000-000000000002")})
    void locksTheCardOnceTheAttemptBudgetIsSpent() {
        String cardId = issueCard();
        given().contentType(ContentType.JSON).body(pin("1111"))
                .when().put("/api/v1/cards/{id}/pin", cardId).then().statusCode(200);

        for (int attempt = 0; attempt < 5; attempt++) {
            given().contentType(ContentType.JSON).body(pin("2222"))
                    .when().post("/api/v1/cards/{id}/number", cardId)
                    .then().statusCode(attempt < 4 ? 403 : 423);
        }

        // The right PIN no longer helps: guessing is over until a new PIN is set.
        given().contentType(ContentType.JSON).body(pin("1111"))
                .when().post("/api/v1/cards/{id}/number", cardId)
                .then().statusCode(423).body("code", equalTo("CARD_PIN_LOCKED"));

        given().contentType(ContentType.JSON).body(pin("3333"))
                .when().put("/api/v1/cards/{id}/pin", cardId).then().statusCode(200);
        given().contentType(ContentType.JSON).body(pin("3333"))
                .when().post("/api/v1/cards/{id}/number", cardId).then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "fffffff1-0000-0000-0000-000000000003")})
    void neverExposesTheNumberOnTheCardItself() {
        issueCard();

        given().when().get("/api/v1/cards")
                .then().statusCode(200)
                .body("[0].lastFourDigits", matchesPattern("[0-9]{4}"))
                .body("[0]", not(org.hamcrest.Matchers.hasKey("number")))
                .body("[0]", not(org.hamcrest.Matchers.hasKey("cardNumber")));
    }

    private static String issueCard() {
        return given().when().post("/api/v1/cards/self-service").then().extract().path("id");
    }

    private static String pin(String value) {
        return "{\"pin\":\"" + value + "\"}";
    }
}
