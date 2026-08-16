package com.cezicola.card.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class FinanceResourceTest {
    @Test
    void topUpConfigureInterestPurchaseAndAggregateWithinTenant() {
        String tenant = UUID.randomUUID().toString();
        String customer = UUID.randomUUID().toString();

        given().contentType(ContentType.JSON).header("X-Tenant-Id", tenant)
                .body("{\"monthlyRate\":0.10}").put("/api/v1/admin/interest-policy")
                .then().statusCode(200).body("monthlyRate", equalTo(0.10f));

        given().contentType(ContentType.JSON).header("X-Tenant-Id", tenant)
                .body("{\"customerId\":\"" + customer + "\",\"amount\":200.00}")
                .post("/api/v1/wallet/top-ups").then().statusCode(201).body("balance", equalTo(200.00f));

        given().contentType(ContentType.JSON).header("X-Tenant-Id", tenant)
                .body("{\"customerId\":\"" + customer + "\",\"merchantCategory\":\"BAKERY\",\"amount\":100.00,\"installments\":2}")
                .post("/api/v1/purchases").then().statusCode(201)
                .body("interest", equalTo(21.00f)).body("total", equalTo(121.00f))
                .body("remainingWalletBalance", equalTo(79.00f));

        given().header("X-Tenant-Id", tenant).get("/api/v1/admin/summary").then().statusCode(200)
                .body("customerWallets", equalTo(1)).body("totalWalletBalance", equalTo(79.00f))
                .body("purchasePrincipal", equalTo(100.00f)).body("interestRevenue", equalTo(21.00f));
    }

    @Test
    void preventsCrossTenantWalletUse() {
        String customer = UUID.randomUUID().toString();
        given().contentType(ContentType.JSON).header("X-Tenant-Id", UUID.randomUUID().toString())
                .body("{\"customerId\":\"" + customer + "\",\"amount\":100.00}")
                .post("/api/v1/wallet/top-ups").then().statusCode(201);

        given().contentType(ContentType.JSON).header("X-Tenant-Id", UUID.randomUUID().toString())
                .body("{\"customerId\":\"" + customer + "\",\"merchantCategory\":\"SHOPPING\",\"amount\":10.00,\"installments\":1}")
                .post("/api/v1/purchases").then().statusCode(422).body("code", equalTo("INSUFFICIENT_FUNDS"));
    }
}
