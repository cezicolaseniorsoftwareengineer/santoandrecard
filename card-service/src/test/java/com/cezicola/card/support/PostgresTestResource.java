package com.cezicola.card.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

/**
 * A real PostgreSQL for the tests that cannot be believed on H2.
 *
 * <p>The fast suite runs on H2 in PostgreSQL compatibility mode, which is the
 * right trade for the invariants that are pure arithmetic. It is the wrong trade
 * for row locking: H2 accepts {@code PESSIMISTIC_WRITE} and has its own idea of
 * what it means, so a test that passes there proves nothing about two purchases
 * racing for the same wallet in production. This starts the same engine the
 * service actually runs on.
 *
 * <p>The container is reused for every test that asks for it and stopped by
 * Ryuk when the JVM exits.
 */
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    // Pinned to the version in compose.yaml and in the Kubernetes manifest. A
    // test database on a different major version tests a different database.
    private static final String IMAGE = "postgres:17-alpine";

    private PostgreSQLContainer postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("card_platform")
                .withUsername("card_app")
                .withPassword("card_app_test");
        postgres.start();
        return Map.of(
                "quarkus.datasource.jdbc.url", postgres.getJdbcUrl(),
                "quarkus.datasource.username", postgres.getUsername(),
                "quarkus.datasource.password", postgres.getPassword());
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }
}
