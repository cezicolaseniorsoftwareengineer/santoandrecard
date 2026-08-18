package com.cezicola.card.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Loading the card moves an obligation, it does not create one: the customer is
 * owed the same total before and after, on a different account.
 */
@QuarkusTest
class CardLoadTest {
    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "aaaaaaa1-0000-0000-0000-000000000001")})
    void movesMoneyFromTheWalletOntoTheCard() {
        topUp("500.00");

        given().contentType(ContentType.JSON).body(amount("200.00"))
                .when().post("/api/v1/wallet/card-loads")
                .then().statusCode(201)
                .body("walletBalance", equalTo(300.00f))
                .body("cardBalance", equalTo(200.00f));

        // The wallet read agrees with what the transfer reported.
        given().when().get("/api/v1/wallet")
                .then().statusCode(200)
                .body("balance", equalTo(300.00f))
                .body("cardBalance", equalTo(200.00f));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "aaaaaaa1-0000-0000-0000-000000000002")})
    void refusesToMoveMoreThanTheWalletHolds() {
        topUp("100.00");

        given().contentType(ContentType.JSON).body(amount("100.01"))
                .when().post("/api/v1/wallet/card-loads")
                .then().statusCode(422);

        // Nothing moved, so nothing was created on the card either.
        given().when().get("/api/v1/wallet")
                .then().body("balance", equalTo(100.00f)).body("cardBalance", equalTo(0.00f));
    }

    private static void topUp(String value) {
        given().contentType(ContentType.JSON).body(amount(value))
                .when().post("/api/v1/wallet/top-ups").then().statusCode(201);
    }

    private static String amount(String value) {
        return "{\"amount\":" + value + "}";
    }
}
