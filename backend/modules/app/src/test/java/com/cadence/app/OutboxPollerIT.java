package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import com.cadence.platform.outbox.OutboxHandler;
import com.cadence.platform.outbox.OutboxPoller;
import com.cadence.platform.outbox.OutboxPublisher;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.cadence.platform.jooq.Tables.AUDIT_LOG;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox poller against the real database as {@code cadence_app}: cross-tenant claim through {@code outbox_claim},
 * per-event tenant scope, savepoint per event, attempt limit for poison events.
 *
 * <p>The poller component is disabled for ITs ({@code app.outbox.enabled=false} in the base class, which a
 * subclass property cannot override), so this class declares the poller bean itself. Its {@code @Scheduled} run
 * fires once at startup and then only every hour; the test drives {@link OutboxPoller#poll()} directly.
 */
@SpringBootTest(properties = {"app.outbox.poll-ms=3600000", "app.outbox.max-attempts=3"})
@Import(OutboxPollerIT.PollerTestConfig.class)
class OutboxPollerIT extends AbstractTimescaleIT {

    static final String POISON = "TEST_POISON";
    static final String OK = "TEST_OK";
    static final int MAX_ATTEMPTS = 3;

    @TestConfiguration
    static class PollerTestConfig {

        @Bean
        OutboxPoller outboxPoller(
                DSLContext dsl,
                ObjectProvider<OutboxHandler> handlers,
                PlatformTransactionManager transactionManager,
                @Value("${app.outbox.max-attempts}") int maxAttempts
        ) {
            return new OutboxPoller(dsl, handlers, transactionManager, maxAttempts);
        }

        @Bean
        RecordingHandler okHandler(DSLContext dsl) {
            return new RecordingHandler(OK, dsl, false);
        }

        @Bean
        RecordingHandler poisonHandler(DSLContext dsl) {
            return new RecordingHandler(POISON, dsl, true);
        }
    }

    /** Writes a marker audit row (a side effect the savepoint must roll back on failure), then maybe throws. */
    static final class RecordingHandler implements OutboxHandler {

        final String type;
        final DSLContext dsl;
        final boolean fail;
        final List<UUID> tenantsSeen = new CopyOnWriteArrayList<>();
        final List<ActorContext.Role> rolesSeen = new CopyOnWriteArrayList<>();

        RecordingHandler(String type, DSLContext dsl, boolean fail) {
            this.type = type;
            this.dsl = dsl;
            this.fail = fail;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public void handle(Record event) {
            tenantsSeen.add(TenantContext.require());
            rolesSeen.add(ActorContext.require().role());
            dsl.insertInto(AUDIT_LOG)
                    .set(AUDIT_LOG.ID, UUID.randomUUID())
                    .set(AUDIT_LOG.TENANT_ID, TenantContext.require())
                    .set(AUDIT_LOG.ACTOR_TYPE, "SYSTEM")
                    .set(AUDIT_LOG.ACTION, "outbox.it." + type)
                    .set(AUDIT_LOG.ENTITY_TYPE, "test")
                    .set(AUDIT_LOG.CREATED_AT, Utc.toLocal(Instant.now()))
                    .execute();
            if (fail) {
                throw new IllegalStateException("poison event " + type);
            }
        }
    }

    @Autowired
    OutboxPoller poller;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    RecordingHandler okHandler;

    @Autowired
    RecordingHandler poisonHandler;

    @Test
    void poisonEventIsRetriedUpToMaxAttemptsWithoutBlockingLaterEvents() throws Exception {
        UUID poisonAggregate = UUID.randomUUID();
        UUID okAggregate = UUID.randomUUID();
        // Poison first: it is older, so it is claimed before the normal event in every batch it appears in.
        publish(POISON, poisonAggregate);
        publish(OK, okAggregate);

        // Seed bookings leave ~200 BOOKING_CREATED events ahead of ours (batch size 25): drain with a bound.
        for (int i = 0; i < 40 && !(processed(okAggregate) && attempts(poisonAggregate) >= MAX_ATTEMPTS); i++) {
            poller.poll();
        }

        assertThat(processed(okAggregate)).as("normal event processed despite the older poison event").isTrue();
        assertThat(lastError(okAggregate)).isNull();
        assertThat(okHandler.tenantsSeen).containsOnly(DemoIds.LUMEN);
        assertThat(okHandler.rolesSeen).containsOnly(ActorContext.Role.SYSTEM);
        assertThat(markerRows(OK)).as("handler side effect committed with processed_at").isEqualTo(1);

        assertThat(processed(poisonAggregate)).isFalse();
        assertThat(attempts(poisonAggregate)).isEqualTo(MAX_ATTEMPTS);
        assertThat(lastError(poisonAggregate)).contains("poison event");
        assertThat(poisonHandler.tenantsSeen).hasSize(MAX_ATTEMPTS).containsOnly(DemoIds.LUMEN);
        assertThat(markerRows(POISON)).as("failed handler side effects rolled back to the savepoint").isZero();

        for (int i = 0; i < 3; i++) {
            poller.poll();
        }
        assertThat(attempts(poisonAggregate)).as("no longer claimed after max attempts").isEqualTo(MAX_ATTEMPTS);
        assertThat(poisonHandler.tenantsSeen).hasSize(MAX_ATTEMPTS);
    }

    private void publish(String type, UUID aggregateId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try (var ignored = TenantContext.open(DemoIds.LUMEN)) {
                publisher.publish(type, "test", aggregateId, "{}");
            }
        });
    }

    // Assertions read as the owner (bypasses RLS), independent of the code under test.

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private static boolean processed(UUID aggregateId) throws SQLException {
        return Boolean.TRUE.equals(queryOne(
                "select processed_at is not null from outbox_event where aggregate_id = ?", aggregateId, Boolean.class));
    }

    private static int attempts(UUID aggregateId) throws SQLException {
        return queryOne("select attempts from outbox_event where aggregate_id = ?", aggregateId, Integer.class);
    }

    private static String lastError(UUID aggregateId) throws SQLException {
        return queryOne("select last_error from outbox_event where aggregate_id = ?", aggregateId, String.class);
    }

    private static long markerRows(String type) throws SQLException {
        return queryOne("select count(*) from audit_log where action = ?", "outbox.it." + type, Long.class);
    }

    private static <T> T queryOne(String sql, Object arg, Class<T> type) throws SQLException {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row for %s", arg).isTrue();
                return rs.getObject(1, type);
            }
        }
    }
}
