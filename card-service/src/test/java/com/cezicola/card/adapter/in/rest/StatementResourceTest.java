package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.BillingService;
import com.cezicola.card.application.CardService;
import com.cezicola.card.application.FinanceService;
import com.cezicola.card.domain.BillingCycle;
import com.cezicola.card.domain.FundingSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The statement endpoints, through the edge that authorises them.
 *
 * <p>A statement is a claim on one customer, so the identity it answers for
 * matters as much as the arithmetic. Everything here is scoped to the token, and
 * the tests that matter most are the ones asking what a caller gets when they
 * name somebody else's statement.
 */
@QuarkusTest
class StatementResourceTest {

    private static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000010";
    // One customer per test. They share a tenant and a billing cycle, so a
    // shared identity would let the first test close the cycle and every test
    // after it would silently assert against that statement instead of its own.
    private static final String LISTING = "c0000010-0000-0000-0000-000000000001";
    private static final String PAYING = "c0000010-0000-0000-0000-000000000002";
    private static final String REPLAYING = "c0000010-0000-0000-0000-000000000003";
    private static final String OVERPAYING = "c0000010-0000-0000-0000-000000000004";
    private static final String INTRUDER = "c0000010-0000-0000-0000-000000000005";
    private static final String VICTIM = "c0000010-0000-0000-0000-000000000006";
    private static final String CLOSING = "c0000010-0000-0000-0000-000000000007";
    private static final String MALFORMED = "c0000010-0000-0000-0000-000000000008";

    @Inject FinanceService finance;
    @Inject BillingService billing;
    @Inject CardService cards;

    /** A closed cycle with one credit purchase on it, set up through the services. */
    private UUID closedStatementFor(String customer, String amount) {
        UUID tenant = UUID.fromString(TENANT_A);
        UUID customerId = UUID.fromString(customer);
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        cards.issueForCustomer(tenant, customerId);
        finance.purchase(tenant, customerId, "RETAIL", new BigDecimal(amount), 1, FundingSource.CREDIT);
        return billing.close(tenant, customerId, BillingCycle.containing(Instant.now())).id();
    }

    @Test
    @TestSecurity(user = "customer-1", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = LISTING)})
    void listsTheCallersOwnStatements() {
        closedStatementFor(LISTING, "150.00");

        given().get("/api/v1/statements")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].cycle", notNullValue())
                .body("[0].status", equalTo("CLOSED"));
    }

    @Test
    @TestSecurity(user = "customer-1", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = PAYING)})
    void paysAStatementAndReportsWhatIsLeft() {
        UUID statementId = closedStatementFor(PAYING, "200.00");
        finance.topUp(UUID.fromString(TENANT_A), UUID.fromString(PAYING), new BigDecimal("500.00"));

        given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "pay-" + statementId)
                .body("{\"amount\":80.00}")
                .post("/api/v1/statements/" + statementId + "/payments")
                .then().statusCode(201)
                .body("status", equalTo("PARTIALLY_PAID"))
                .body("balance", equalTo(120.00f));
    }

    @Test
    @TestSecurity(user = "customer-1", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = REPLAYING)})
    void payingTwiceWithTheSameKeyChargesOnce() {
        UUID statementId = closedStatementFor(REPLAYING, "300.00");
        finance.topUp(UUID.fromString(TENANT_A), UUID.fromString(REPLAYING), new BigDecimal("500.00"));
        String key = "replay-" + statementId;

        for (int attempt = 0; attempt < 2; attempt++) {
            given().contentType(ContentType.JSON)
                    .header("Idempotency-Key", key)
                    .body("{\"amount\":100.00}")
                    .post("/api/v1/statements/" + statementId + "/payments")
                    .then().statusCode(201)
                    // The replay returns the first outcome rather than paying again.
                    .body("balance", equalTo(200.00f));
        }
    }

    @Test
    @TestSecurity(user = "customer-1", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = OVERPAYING)})
    void payingMoreThanIsOwedIsRefusedWithAStateError() {
        UUID statementId = closedStatementFor(OVERPAYING, "50.00");
        finance.topUp(UUID.fromString(TENANT_A), UUID.fromString(OVERPAYING), new BigDecimal("500.00"));

        given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "over-" + statementId)
                .body("{\"amount\":90.00}")
                .post("/api/v1/statements/" + statementId + "/payments")
                // 422 rather than 400: the request is well formed and retrying it
                // unchanged will fail the same way until the statement changes.
                .then().statusCode(422)
                .body("code", equalTo("STATEMENT_STATE"));
    }

    @Test
    @TestSecurity(user = "customer-2", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = INTRUDER)})
    void anotherCustomersStatementCannotBePaid() {
        UUID theirStatement = closedStatementFor(VICTIM, "400.00");
        finance.topUp(UUID.fromString(TENANT_A), UUID.fromString(INTRUDER), new BigDecimal("900.00"));

        given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "cross-" + theirStatement)
                .body("{\"amount\":10.00}")
                .post("/api/v1/statements/" + theirStatement + "/payments")
                // The same answer as absent: confirming that it exists but is not
                // theirs would leak the existence of another customer's bill.
                .then().statusCode(404)
                .body("code", equalTo("STATEMENT_NOT_FOUND"));
    }

    @Test
    @TestSecurity(user = "customer-1", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = CLOSING)})
    void closesACycleOnDemandAndReturnsItsItems() {
        UUID tenant = UUID.fromString(TENANT_A);
        UUID customer = UUID.fromString(CLOSING);
        finance.setInterestPolicy(tenant, BigDecimal.ZERO);
        cards.issueForCustomer(tenant, customer);
        finance.purchase(tenant, customer, "BAKERY", new BigDecimal("35.00"), 1, FundingSource.CREDIT);
        String cycle = BillingCycle.containing(Instant.now()).reference();

        String id = given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "close-" + customer)
                .body("{\"cycle\":\"" + cycle + "\"}")
                .post("/api/v1/statements/close")
                .then().statusCode(201)
                .body("cycle", equalTo(cycle))
                .extract().path("id");

        given().get("/api/v1/statements/" + id + "/items")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].sourceType", equalTo("PURCHASE"));
    }

    @Test
    @TestSecurity(user = "customer-1", roles = "customer")
    @OidcSecurity(claims = {
            @Claim(key = "tenant_id", value = TENANT_A),
            @Claim(key = "customer_id", value = MALFORMED)})
    void aMalformedCycleIsRejectedBeforeAnythingIsBilled() {
        given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "bad-cycle")
                .body("{\"cycle\":\"agosto\"}")
                .post("/api/v1/statements/close")
                .then().statusCode(400);
    }

    @Test
    void statementsRequireAuthentication() {
        // Deny by default on every /api path, statements included.
        given().get("/api/v1/statements").then().statusCode(401);
    }
}
