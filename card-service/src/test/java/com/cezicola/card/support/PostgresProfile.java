package com.cezicola.card.support;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;
import java.util.Map;

/**
 * Runs the service against real PostgreSQL, with the real migrations.
 *
 * <p>The database kind is fixed at build time, so selecting this profile
 * re-augments the application rather than reconfiguring the running one. That
 * cost is the point: Flyway applies the same twelve migrations CI applies, and
 * Hibernate validates the mapping against the types PostgreSQL actually created
 * instead of the ones H2 was willing to invent.
 */
public class PostgresProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.db-kind", "postgresql",
                "quarkus.flyway.migrate-at-start", "true",
                "quarkus.hibernate-orm.schema-management.strategy", "validate",
                // Concurrency tests hold several connections at once; the default
                // floor is sized for a single-threaded suite.
                "quarkus.datasource.jdbc.max-size", "20");
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(PostgresTestResource.class));
    }
}
