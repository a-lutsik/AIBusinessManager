package com.cadence.app;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the app against TimescaleDB with the production role split: Flyway migrates as the owner {@code cadence},
 * the pool connects as {@code cadence_app} (created by V7, subject to RLS). No {@code @ServiceConnection}: it would
 * force the container's owner credentials onto the application datasource and silently bypass RLS.
 *
 * <p>One container per JVM (started on first use, removed by Testcontainers at exit), not per test class: Spring
 * caches the application context across IT classes, and a per-class {@code @Container} would restart the database
 * on a new port behind a cached context. {@code disabledWithoutDocker} still skips the ITs without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractTimescaleIT {

    static final DockerImageName IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.30.0-pg16")
            .asCompatibleSubstituteFor("postgres");

    static final String OWNER_USER = "cadence";
    static final String OWNER_PASSWORD = "cadence";
    static final String APP_USER = "cadence_app";
    static final String APP_PASSWORD = "cadence_app";

    static final PostgreSQLContainer DB = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("cadence")
            .withUsername(OWNER_USER)
            .withPassword(OWNER_PASSWORD);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        DB.start();
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> OWNER_USER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> APP_PASSWORD);
        registry.add("app.persistence.require-transaction", () -> "true");
        registry.add("app.seed.enabled", () -> "true");
        registry.add("app.outbox.enabled", () -> "false");
        registry.add("spring.batch.job.enabled", () -> "false");
        registry.add("app.ai.provider", () -> "stub");
    }
}
