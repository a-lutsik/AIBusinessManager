package com.cadence.app;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractTimescaleIT {

    static final DockerImageName IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.30.0-pg16")
            .asCompatibleSubstituteFor("postgres");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer DB = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("cadence")
            .withUsername("cadence")
            .withPassword("cadence");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.seed.enabled", () -> "true");
        registry.add("app.outbox.enabled", () -> "false");
        registry.add("app.security.oauth2-enabled", () -> "false");
        registry.add("spring.batch.job.enabled", () -> "false");
        registry.add("app.ai.provider", () -> "stub");
    }
}
