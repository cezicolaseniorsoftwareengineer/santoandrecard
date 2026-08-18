package com.cezicola.card.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

/**
 * What the API answers while the identity provider is unreachable.
 *
 * <p>This was found by running the packaged service against the canonical
 * database with Keycloak down: every protected path answered 500 with a stack
 * trace. The refusal was correct — authentication fails closed — but the status
 * told clients their request was malformed, and a client that believes it sent
 * something invalid does not retry a request that would now succeed.
 *
 * <p>The profile points OIDC at a port nothing listens on, which is the same
 * condition without needing Keycloak to be absent.
 */
@QuarkusTest
@TestProfile(IdentityProviderUnavailableTest.ProviderDownProfile.class)
class IdentityProviderUnavailableTest {

    public static class ProviderDownProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    // The rest of the suite disables OIDC and injects identities
                    // directly; here the point is precisely that it is enabled
                    // and cannot be reached.
                    "quarkus.oidc.tenant-enabled", "true",
                    "quarkus.oidc.auth-server-url", "http://localhost:1/realms/nothing-listens-here",
                    "quarkus.oidc.client-id", "card-service",
                    "quarkus.oidc.connection-retry-count", "1",
                    "quarkus.oidc.connection-timeout", "1S");
        }
    }

    @Test
    void answersServiceUnavailableRatherThanServerError() {
        given().when().get("/api/v1/cards")
                .then()
                .statusCode(503)
                .header("Retry-After", "5")
                .body("code", equalTo("IDENTITY_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void refusesEvenWithATokenPresent() {
        // Nothing verified this token, and nothing could have. The answer must
        // not depend on what the caller sent.
        given().header("Authorization", "Bearer not-a-real-token")
                .when().post("/api/v1/cards")
                .then().statusCode(503);
    }

    @Test
    void tellsAnUnauthenticatedCallerNothingAboutTheDeployment() {
        String body = given().when().get("/api/v1/wallet").then().statusCode(503)
                .extract().body().asString();

        // No provider URL, no host, no stack trace, no Quarkus error id.
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("localhost")));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("realms")));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("error id")));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("OIDC")));
    }

    @Test
    void keepsOperationalEndpointsAvailable() {
        // Health must not depend on identity, or an outage in the provider would
        // make the service look dead to whatever is deciding to restart it.
        given().when().get("/q/health").then().statusCode(200);
    }
}
