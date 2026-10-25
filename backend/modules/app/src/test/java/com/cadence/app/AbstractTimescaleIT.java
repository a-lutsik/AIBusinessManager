package com.cadence.app;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the app against TimescaleDB with the production role split: Flyway migrates as the owner {@code cadence},
 * the pool connects as {@code cadence_app} (created by V7, subject to RLS). No {@code @ServiceConnection}: it would
 * force the container's owner credentials onto the application datasource and silently bypass RLS.
 *
 * <p>One container per JVM (started on first use, removed by Testcontainers at exit), not per test class: Spring
 * caches the application context across IT classes, and a per-class {@code @Container} would restart the database
 * on a new port behind a cached context.
 *
 * <p>Without Docker the ITs are skipped on a developer machine, but they FAIL when the {@code CI} environment variable
 * is set (GitHub Actions sets it): a runner without a reachable Docker daemon must never turn the build green with
 * every integration test silently skipped. See {@link DockerRequiredCondition}.
 */
@ExtendWith(AbstractTimescaleIT.DockerRequiredCondition.class)
public abstract class AbstractTimescaleIT {

    /**
     * Docker reachable: run. Not reachable and {@code CI} set: fail the test class (an exception thrown by an
     * {@link ExecutionCondition} is reported by JUnit as a failure of the container, not as a skip). Not reachable
     * locally: skip with a reason.
     */
    static final class DockerRequiredCondition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker is available");
            }
            String ci = System.getenv("CI");
            if (ci != null && !ci.isBlank() && !"false".equalsIgnoreCase(ci)) {
                throw new ExtensionConfigurationException(
                        "Docker is not reachable but CI is set: integration tests must run in CI, not be skipped");
            }
            return ConditionEvaluationResult.disabled("Docker is not available (unset CI to skip locally)");
        }
    }

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
        // ADR 0001 / review M3: recalculations fold the journal as of now minus this lag. Short in ITs, so a test only
        // waits a moment for its writes to fall before the as-of instant (MetricsJournalReplayIT.AS_OF_LAG).
        registry.add("app.metrics.as-of-lag", () -> "PT2S");
    }
}
