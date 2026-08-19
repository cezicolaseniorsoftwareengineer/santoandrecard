package com.cezicola.card.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class FinanceResourceTest {
    private static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String TENANT_B = "bbbbbbbb-0000-0000-0000-000000000002";

    @jakarta.inject.Inject
    com.cezicola.card.application.FinanceService financeService;

    @Test
    @TestSecurity(user = "admin-a", roles = "admin")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_A)})
    void configuresInterestPolicyAsAdmin() {
        given().contentType(ContentType.JSON)
                .body("{\"monthlyRate\":0.10}").put("/api/v1/admin/interest-policy")
                .then().statusCode(200).body("monthlyRate", equalTo(0.10f));
    }

    @Test
    @TestSecurity(user = "customer-1", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = "c0000001-0000-0000-0000-000000000001")})
    void topsUpAndPurchasesUsingTheIdentityFromTheToken() {
        // Set the rate through the service so this test does not depend on another
        // test method having run first.
        financeService.setInterestPolicy(java.util.UUID.fromString(TENANT_A), new java.math.BigDecimal("0.10"));

        given().contentType(ContentType.JSON)
                .body("{\"amount\":200.00}")
                .header("Idempotency-Key", key()).post("/api/v1/wallet/top-ups").then().statusCode(201).body("balance", equalTo(200.00f));

        // The card is what pays for a purchase, so the money has to reach it first.
        given().contentType(ContentType.JSON)
                .body("{\"amount\":200.00}")
                .header("Idempotency-Key", key()).post("/api/v1/wallet/card-loads").then().statusCode(201)
                .body("cardBalance", equalTo(200.00f));

        given().contentType(ContentType.JSON)
                .body("{\"merchantCategory\":\"BAKERY\",\"amount\":100.00,\"installments\":2}")
                .header("Idempotency-Key", key()).post("/api/v1/purchases").then().statusCode(201)
                .body("customerId", equalTo("c0000001-0000-0000-0000-000000000001"))
                .body("monthlyRate", equalTo(0.10f))
                .body("remainingCardBalance", equalTo(79.00f));
    }

    @Test
    @TestSecurity(user = "customer-2", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = "c0000002-0000-0000-0000-000000000002")})
    void fundsTheWalletOfTenantA() {
        given().contentType(ContentType.JSON).body("{\"amount\":100.00}")
                .header("Idempotency-Key", key()).post("/api/v1/wallet/top-ups").then().statusCode(201);
    }

    @Test
    @TestSecurity(user = "customer-2-other-tenant", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_B),
            @Claim(key = "customer_id", value = "c0000002-0000-0000-0000-000000000002")})
    void cannotSpendTheSameCustomerIdentifierAcrossTenants() {
        given().contentType(ContentType.JSON)
                .body("{\"merchantCategory\":\"SHOPPING\",\"amount\":10.00,\"installments\":1}")
                .header("Idempotency-Key", key()).post("/api/v1/purchases").then().statusCode(422).body("code", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    @TestSecurity(user = "customer-3", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = "c0000003-0000-0000-0000-000000000003")})
    void failsClosedWhenMerchantAuthorizationNetworkIsUnavailable() {
        given().contentType(ContentType.JSON).body("{\"amount\":100.00}")
                .header("Idempotency-Key", key()).post("/api/v1/wallet/top-ups").then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"merchantCategory\":\"NETWORK_FAILURE\",\"amount\":10.00,\"installments\":1}")
                .header("Idempotency-Key", key()).post("/api/v1/purchases").then().statusCode(503)
                .header("Retry-After", "5")
                .body("code", equalTo("MERCHANT_AUTHORIZATION_UNAVAILABLE"));
    }

    @Test
    void rejectsUnauthenticatedAccess() {
        given().get("/api/v1/admin/summary").then().statusCode(401);
        given().contentType(ContentType.JSON).body("{\"amount\":10.00}")
                .header("Idempotency-Key", key()).post("/api/v1/wallet/top-ups").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "customer-4", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = "c0000004-0000-0000-0000-000000000004")})
    void deniesAdministrativeEndpointsToCustomers() {
        given().get("/api/v1/admin/summary").then().statusCode(403);
        given().contentType(ContentType.JSON).body("{\"monthlyRate\":0.01}")
                .put("/api/v1/admin/interest-policy").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "claimless", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_A)})
    void refusesAuthenticatedTokenWithoutCustomerClaim() {
        given().contentType(ContentType.JSON).body("{\"amount\":10.00}")
                .header("Idempotency-Key", key()).post("/api/v1/wallet/top-ups").then().statusCode(403)
                .body("code", equalTo("IDENTITY_CLAIM_MISSING"));
    }

    @Test
    @TestSecurity(user = "operational", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_A)})
    void keepsOperationalEndpointsReachable() {
        given().get("/q/health/ready").then().statusCode(200);
    }

    /** A fresh key per call: these tests exercise distinct operations, not replays. */
    private static String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
