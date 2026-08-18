package com.cezicola.card.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cardholder issues their own card. What has to hold is that they get
 * exactly one, that they cannot influence its limit, and that neither a repeat
 * nor two simultaneous requests produce a second card carrying a second limit.
 */
@QuarkusTest
class SelfServiceIssuanceTest {
    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String HOLDER = "eeeeeeee-0000-0000-0000-000000000001";
    private static final String REPEAT_HOLDER = "eeeeeeee-0000-0000-0000-000000000002";
    private static final String CONCURRENT_HOLDER = "eeeeeeee-0000-0000-0000-000000000003";

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID), @Claim(key = "customer_id", value = HOLDER)})
    void issuesAPlatinumCardToTheCallingCustomer() {
        given().when().post("/api/v1/cards/self-service")
                .then().statusCode(201)
                .body("customerId", equalTo(HOLDER))
                .body("product", equalTo("PLATINUM"))
                .body("productName", equalTo("Santo André Card Platinum"))
                .body("status", equalTo("ACTIVE"))
                .body("currency", equalTo("BRL"))
                .body("creditLimit", equalTo(5000.00f))
                .body("lastFourDigits", matchesPattern("[0-9]{4}"));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = REPEAT_HOLDER)})
    void answersARepeatWithTheCardAlreadyHeld() {
        String first = given().when().post("/api/v1/cards/self-service")
                .then().statusCode(201).extract().path("id");

        // 200 rather than 201: nothing was created this time.
        String second = given().when().post("/api/v1/cards/self-service")
                .then().statusCode(200).extract().path("id");

        assertEquals(first, second);
        given().when().get("/api/v1/cards")
                .then().statusCode(200).body("size()", equalTo(1));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = CONCURRENT_HOLDER)})
    void doesNotMintASecondLimitWhenRequestsOverlap() throws Exception {
        Callable<String> issue = () -> given().when().post("/api/v1/cards/self-service")
                .then().statusCode(anyOf201Or200()).extract().path("id");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<String> issued = pool.invokeAll(List.of(issue, issue, issue, issue)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .distinct()
                    .toList();

            // A single identifier across four overlapping requests is the whole
            // point: a second one would be a second credit limit.
            assertEquals(1, issued.size(), "overlapping requests issued more than one card");
        } finally {
            pool.shutdownNow();
        }
    }

    private static org.hamcrest.Matcher<Integer> anyOf201Or200() {
        return org.hamcrest.Matchers.anyOf(equalTo(201), equalTo(200));
    }
}
