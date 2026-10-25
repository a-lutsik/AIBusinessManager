package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review D1: the demo seed recalculates right after writing its visits, so it must not fold the journal at
 * {@code now − app.metrics.as-of-lag} (the visits would all be newer than T and every metric "not enough data" until
 * the nightly job). The app starts on a fresh database of its own, with the production default lag (no override here),
 * the seeder runs at startup, and Lumen's seed snapshot must have metrics with enough data that replay equal by SQL.
 *
 * <p>Not an {@link AbstractTimescaleIT} subclass: that one points every context at the shared database and sets a
 * short lag. Same container, same role split (Flyway as the owner, the pool as {@code cadence_app}).
 */
@SpringBootTest
@ExtendWith(AbstractTimescaleIT.DockerRequiredCondition.class)
class SeedMetricsIT {

    private static final String DATABASE = "seed_" + UUID.randomUUID().toString().replace("-", "");

    private static String url() {
        return "jdbc:postgresql://" + AbstractTimescaleIT.DB.getHost() + ":" + AbstractTimescaleIT.DB.getMappedPort(5432)
                + "/" + DATABASE;
    }

    @DynamicPropertySource
    static void freshDatabase(DynamicPropertyRegistry registry) throws SQLException {
        AbstractTimescaleIT.DB.start();
        try (Connection c = DriverManager.getConnection(AbstractTimescaleIT.DB.getJdbcUrl(),
                AbstractTimescaleIT.OWNER_USER, AbstractTimescaleIT.OWNER_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + DATABASE);
        }
        registry.add("spring.datasource.url", SeedMetricsIT::url);
        registry.add("spring.datasource.username", () -> AbstractTimescaleIT.APP_USER);
        registry.add("spring.datasource.password", () -> AbstractTimescaleIT.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> AbstractTimescaleIT.OWNER_USER);
        registry.add("spring.flyway.password", () -> AbstractTimescaleIT.OWNER_PASSWORD);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> AbstractTimescaleIT.APP_PASSWORD);
        registry.add("app.persistence.require-transaction", () -> "true");
        registry.add("app.seed.enabled", () -> "true");
        registry.add("app.outbox.enabled", () -> "false");
        registry.add("spring.batch.job.enabled", () -> "false");
        registry.add("app.ai.provider", () -> "stub");
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(url(), AbstractTimescaleIT.OWNER_USER, AbstractTimescaleIT.OWNER_PASSWORD);
    }

    private static long count(String sql, UUID tenant) throws SQLException {
        try (Connection c = owner(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setObject(1, tenant);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void theSeedRecalculationSeesTheSeededVisitsAndReplaysEqual() throws Exception {
        assertThat(count("select count(*) from appointment where tenant_id = ?", DemoIds.LUMEN))
                .as("seeded visits").isGreaterThan(100);
        Instant seedAsOf;
        try (Connection c = owner();
             PreparedStatement st = c.prepareStatement("select max(created_at) from metric_snapshot where tenant_id = ?")) {
            st.setObject(1, DemoIds.LUMEN);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                assertThat(rs.getTimestamp(1)).as("the seed recalculated Lumen").isNotNull();
                seedAsOf = rs.getTimestamp(1).toLocalDateTime().toInstant(ZoneOffset.UTC);
            }
        }
        long sufficient;
        try (Connection c = owner(); PreparedStatement st = c.prepareStatement("""
                select count(*) from metric_snapshot
                where tenant_id = ? and specialist_id is null and not insufficient_data and created_at = ?""")) {
            st.setObject(1, DemoIds.LUMEN);
            st.setTimestamp(2, java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(seedAsOf, ZoneOffset.UTC)));
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                sufficient = rs.getLong(1);
            }
        }
        assertThat(sufficient).as("tenant metrics with enough data in the seed snapshot (both windows)")
                .isGreaterThanOrEqualTo(3);

        MetricsReplay.Outcome outcome = MetricsReplay.assertEqualsSnapshots(SeedMetricsIT::owner, DemoIds.LUMEN, seedAsOf);
        // 2 windows x 6 metrics x (tenant + 3 active masters).
        assertThat(outcome.compared()).isEqualTo(48);
        assertThat(outcome.withValue()).isGreaterThanOrEqualTo(6);
    }
}
