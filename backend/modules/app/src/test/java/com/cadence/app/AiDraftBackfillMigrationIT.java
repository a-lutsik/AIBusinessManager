package com.cadence.app;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V15 on a database with pre-V15 drafts (ADR 0002 §9.4): a fresh database in the IT container is migrated to V14,
 * given legacy {@code ai_draft} rows (conversation ids without a conversation row), then migrated to the latest
 * version. PENDING drafts get {@code expires_at = created_at + 24h}, every legacy conversation id becomes an
 * {@code ai_conversation} of the draft's tenant, and a conversation id shared by two tenants stops the migration with
 * a clear message instead of a duplicate key. No Spring context.
 */
class AiDraftBackfillMigrationIT extends AbstractTimescaleIT {

    private static final UUID TENANT_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID CONVERSATION = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_CONVERSATION = UUID.fromString("40000000-0000-0000-0000-000000000002");

    private String database;
    private String url;

    @BeforeEach
    void freshDatabase() throws SQLException {
        DB.start();
        database = "aibackfill_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + database);
        }
        url = "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/" + database;
    }

    @AfterEach
    void dropDatabase() {
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        } catch (SQLException ignored) {
            // The container is thrown away at JVM exit anyway.
        }
    }

    private Flyway flyway(String target) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(url, OWNER_USER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .placeholders(Map.of("appDbPassword", APP_PASSWORD));
        return (target == null ? config : config.target(target)).load();
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(url, OWNER_USER, OWNER_PASSWORD);
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void tenant(Connection c, UUID id, String slug) throws SQLException {
        exec(c, "insert into tenant (id, slug, display_name, timezone, currency_code, country_code) values ('"
                + id + "', '" + slug + "', 'Legacy', 'Europe/Prague', 'CZK', 'CZ')");
    }

    private static void draft(Connection c, UUID tenant, UUID conversation, String status, String createdAt) throws SQLException {
        exec(c, "insert into ai_draft (id, tenant_id, conversation_id, tool_name, payload, status, created_at) values "
                + "(gen_random_uuid(), '" + tenant + "', '" + conversation + "', 'draft_booking', '{}', '" + status
                + "', timestamp '" + createdAt + "')");
    }

    @Test
    void legacyDraftsGetAConversationOfTheirTenantAndPendingOnesATtl() throws Exception {
        flyway("14").migrate();
        try (Connection c = owner()) {
            tenant(c, TENANT_A, "legacy-a");
            draft(c, TENANT_A, CONVERSATION, "PENDING", "2026-10-01 10:00:00");
            draft(c, TENANT_A, CONVERSATION, "CONFIRMED", "2026-10-01 11:00:00");
        }

        flyway(null).migrate();

        try (Connection c = owner()) {
            assertThat(count(c, "select count(*) from ai_conversation where id = '" + CONVERSATION
                    + "' and tenant_id = '" + TENANT_A + "' and created_at = timestamp '2026-10-01 10:00:00'"
                    + " and last_message_at = timestamp '2026-10-01 11:00:00'")).isEqualTo(1);
            assertThat(count(c, "select count(*) from ai_draft where status = 'PENDING'"
                    + " and expires_at = timestamp '2026-10-02 10:00:00'")).isEqualTo(1);
            assertThat(count(c, "select count(*) from ai_draft where status = 'CONFIRMED' and expires_at is null")).isEqualTo(1);
            exec(c, "delete from ai_conversation where id = '" + CONVERSATION + "'");
            assertThat(count(c, "select count(*) from ai_draft")).as("drafts go with their conversation").isZero();
        }
    }

    @Test
    void aConversationIdSharedByTwoTenantsStopsTheMigrationWithAClearMessage() throws Exception {
        flyway("14").migrate();
        try (Connection c = owner()) {
            tenant(c, TENANT_A, "legacy-a");
            tenant(c, TENANT_B, "legacy-b");
            draft(c, TENANT_A, CONVERSATION, "PENDING", "2026-10-01 10:00:00");
            draft(c, TENANT_B, CONVERSATION, "PENDING", "2026-10-01 10:00:00");
            draft(c, TENANT_B, OTHER_CONVERSATION, "PENDING", "2026-10-01 10:00:00");
        }

        assertThatThrownBy(() -> flyway(null).migrate())
                .hasStackTraceContaining("ai_draft.conversation_id used by more than one tenant")
                .hasStackTraceContaining(CONVERSATION.toString());

        try (Connection c = owner()) {
            assertThat(count(c, "select count(*) from flyway_schema_history where version = '15' and success"))
                    .as("V15 not applied").isZero();
        }
    }
}
