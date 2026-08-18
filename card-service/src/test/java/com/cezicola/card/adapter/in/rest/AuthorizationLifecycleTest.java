package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.AuthorizationService;
import com.cezicola.card.application.LedgerService;
import com.cezicola.card.domain.LedgerAccount;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The whole point of an authorization is that the money is neither spent nor
 * available while the merchant decides. What has to hold at every step is that
 * the customer's total — spendable plus held — only changes when something is
 * actually captured.
 */
@QuarkusTest
class AuthorizationLifecycleTest {
    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID TENANT = UUID.fromString(TENANT_ID);

    @Inject
    LedgerService ledger;

    @Inject
    AuthorizationService authorizations;

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "ccccccc1-0000-0000-0000-000000000001")})
    void holdsFundsWithoutSpendingThem() {
        UUID customer = UUID.fromString("ccccccc1-0000-0000-0000-000000000001");
        topUp("1000.00");

        String id = authorize("250.00").extract().path("id");

        // Out of reach, but not gone: the customer is owed exactly as much as
        // before, in two accounts instead of one.
        given().when().get("/api/v1/wallet").then().body("balance", equalTo(750.00f));
        assertEquals(new BigDecimal("250.00"), heldFor(customer));
        assertEquals(new BigDecimal("1000.00"), owedTo(customer));

        given().when().get("/api/v1/authorizations/{id}", id)
                .then().statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("amount", equalTo(250.00f))
                .body("capturedAmount", equalTo(0.00f));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "ccccccc1-0000-0000-0000-000000000002")})
    void capturesInFullAndOwesTheMerchant() {
        UUID customer = UUID.fromString("ccccccc1-0000-0000-0000-000000000002");
        topUp("1000.00");
        String id = authorize("250.00").extract().path("id");

        capture(id, null).body("status", equalTo("CAPTURED")).body("capturedAmount", equalTo(250.00f));

        given().when().get("/api/v1/wallet").then().body("balance", equalTo(750.00f));
        assertEquals(BigDecimal.ZERO.setScale(2), heldFor(customer));
        // The money left the customer only now, and it left towards the merchant.
        assertEquals(new BigDecimal("750.00"), owedTo(customer));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "ccccccc1-0000-0000-0000-000000000003")})
    void returnsTheUncapturedRemainderToTheWallet() {
        UUID customer = UUID.fromString("ccccccc1-0000-0000-0000-000000000003");
        topUp("1000.00");
        String id = authorize("250.00").extract().path("id");

        capture(id, "90.00").body("capturedAmount", equalTo(90.00f)).body("releasedAmount", equalTo(160.00f));

        // A partial capture must not strand the difference in the held account.
        given().when().get("/api/v1/wallet").then().body("balance", equalTo(910.00f));
        assertEquals(BigDecimal.ZERO.setScale(2), heldFor(customer));
        assertEquals(new BigDecimal("910.00"), owedTo(customer));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "ccccccc1-0000-0000-0000-000000000004")})
    void reversalGivesEverythingBack() {
        UUID customer = UUID.fromString("ccccccc1-0000-0000-0000-000000000004");
        topUp("1000.00");
        String id = authorize("250.00").extract().path("id");

        given().contentType(ContentType.JSON).header("Idempotency-Key", key())
                .when().post("/api/v1/authorizations/{id}/reversal", id)
                .then().statusCode(200).body("status", equalTo("REVERSED"));

        given().when().get("/api/v1/wallet").then().body("balance", equalTo(1000.00f));
        assertEquals(BigDecimal.ZERO.setScale(2), heldFor(customer));
        assertEquals(new BigDecimal("1000.00"), owedTo(customer));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "ccccccc1-0000-0000-0000-000000000005")})
    void refusesToSettleAHoldTwice() {
        topUp("1000.00");
        String id = authorize("100.00").extract().path("id");
        capture(id, null);

        // A settled hold is finished. Retrying with a new key is a new request,
        // and it must be refused on state rather than replayed.
        given().contentType(ContentType.JSON).header("Idempotency-Key", key())
                .when().post("/api/v1/authorizations/{id}/capture", id)
                .then().statusCode(409).body("code", equalTo("AUTHORIZATION_STATE"));

        given().contentType(ContentType.JSON).header("Idempotency-Key", key())
                .when().post("/api/v1/authorizations/{id}/reversal", id)
                .then().statusCode(409).body("code", equalTo("AUTHORIZATION_STATE"));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "ccccccc1-0000-0000-0000-000000000006")})
    void refusesToCaptureMoreThanWasHeld() {
        topUp("1000.00");
        String id = authorize("100.00").extract().path("id");

        given().contentType(ContentType.JSON).header("Idempotency-Key", key())
                .body("{\"amount\":100.01}")
                .when().post("/api/v1/authorizations/{id}/capture", id)
                .then().statusCode(409).body("code", equalTo("AUTHORIZATION_STATE"));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "ccccccc1-0000-0000-0000-000000000007")})
    void refusesToHoldMoreThanTheWalletHas() {
        topUp("50.00");

        given().contentType(ContentType.JSON).header("Idempotency-Key", key())
                .body(authorizeBody("50.01"))
                .when().post("/api/v1/authorizations")
                .then().statusCode(422).body("code", equalTo("INSUFFICIENT_FUNDS"));

        given().when().get("/api/v1/wallet").then().body("balance", equalTo(50.00f));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "ccccccc1-0000-0000-0000-000000000008")})
    void replaysAnAuthorizationInsteadOfHoldingTwice() {
        topUp("1000.00");
        String key = key();
        // The very same bytes twice. A retry sends the request it already sent;
        // a different body under the same key is a different intent, and the
        // conflict check exists to refuse exactly that.
        String body = authorizeBody("300.00");

        String first = given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                .body(body).when().post("/api/v1/authorizations")
                .then().statusCode(201).extract().path("id");
        String again = given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                .body(body).when().post("/api/v1/authorizations")
                .then().statusCode(201).extract().path("id");

        assertEquals(first, again);
        // One hold, not two: a retried authorization must not put a second slice
        // of the customer's money out of reach.
        given().when().get("/api/v1/wallet").then().body("balance", equalTo(700.00f));
    }

    @Test
    @TestSecurity(user = "holder", roles = "customer")
    @OidcSecurity(claims = {@Claim(key = "tenant_id", value = TENANT_ID),
            @Claim(key = "customer_id", value = "ccccccc1-0000-0000-0000-000000000009")})
    void releasesHoldsNobodyCaptured() {
        UUID customer = UUID.fromString("ccccccc1-0000-0000-0000-000000000009");
        topUp("1000.00");
        String id = authorize("400.00").extract().path("id");

        // Driven directly rather than waited for: the test asserts what expiry
        // does, not when the scheduler happens to fire.
        expire(UUID.fromString(id));

        given().when().get("/api/v1/authorizations/{id}", id).then().body("status", equalTo("EXPIRED"));
        given().when().get("/api/v1/wallet").then().body("balance", equalTo(1000.00f));
        assertEquals(BigDecimal.ZERO.setScale(2), heldFor(customer));
    }

    /** Spendable plus held: what the issuer owes this customer, whatever the state. */
    private BigDecimal owedTo(UUID customer) {
        return ledger.balanceOf(TENANT, LedgerAccount.CUSTOMER_WALLET, customer).add(heldFor(customer));
    }

    private BigDecimal heldFor(UUID customer) {
        return ledger.balanceOf(TENANT, LedgerAccount.CUSTOMER_HELD, customer);
    }

    private void expire(UUID id) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> authorizations.expireOne(id));
    }

    private static io.restassured.response.ValidatableResponse authorize(String amount) {
        return given().contentType(ContentType.JSON).header("Idempotency-Key", key())
                .body(authorizeBody(amount))
                .when().post("/api/v1/authorizations").then().statusCode(201);
    }

    private static io.restassured.response.ValidatableResponse capture(String id, String amount) {
        var request = given().contentType(ContentType.JSON).header("Idempotency-Key", key());
        if (amount != null) {
            request = request.body("{\"amount\":" + amount + "}");
        }
        return request.when().post("/api/v1/authorizations/{id}/capture", id).then().statusCode(200);
    }

    private static String authorizeBody(String amount) {
        return "{\"cardId\":\"" + UUID.randomUUID() + "\",\"merchantCategory\":\"Shopping\",\"amount\":"
                + amount + "}";
    }

    private static void topUp(String amount) {
        given().contentType(ContentType.JSON).header("Idempotency-Key", key())
                .body("{\"amount\":" + amount + "}")
                .when().post("/api/v1/wallet/top-ups").then().statusCode(201);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
