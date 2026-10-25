package com.cadence.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V13 on a database that holds pre-V13 data (ADR 0001 §2, §6): a fresh database in the IT container is migrated to
 * V12, given old-style journal rows, then migrated to the latest version. CREATED payloads become v2 with the start the
 * visit was created with and {@code backfilled:true}; visits without CREATED get one; growth_signal becomes a plain,
 * deduplicated table with RLS, its old rows are not carried over. No Spring context.
 */
class JournalBackfillMigrationIT extends AbstractTimescaleIT {

    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MASTER = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SERVICE = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID CLIENT = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID MOVED = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SILENT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID NO_CREATED = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID ALREADY_V2 = UUID.fromString("20000000-0000-0000-0000-000000000004");

    private final ObjectMapper mapper = new ObjectMapper();
    private String database;
    private String url;

    @BeforeEach
    void freshDatabase() throws SQLException {
        DB.start();
        database = "backfill_" + UUID.randomUUID().toString().replace("-", "");
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
        var config = Flyway.configure()
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

    /** Visits as stage 03 wrote them: CREATED {"status":...} only, status and move events with from/to. */
    private void legacyData() throws SQLException {
        try (Connection c = owner()) {
            exec(c, "insert into tenant (id, slug, display_name, timezone, currency_code, country_code) values ('"
                    + TENANT + "', 'legacy', 'Legacy', 'Europe/Prague', 'CZK', 'CZ')");
            exec(c, "insert into specialist (id, tenant_id, display_name, calendar_color, active) values ('"
                    + MASTER + "', '" + TENANT + "', 'Klara', '#0369a1', true)");
            exec(c, "insert into catalog_service (id, tenant_id, name, duration_minutes, price_minor, buffer_before_minutes,"
                    + " buffer_after_minutes, color, active, public_visible) values ('" + SERVICE + "', '" + TENANT
                    + "', 'Cut', 45, 90000, 0, 0, '#0369a1', true, true)");
            exec(c, "insert into client (id, tenant_id, normalized_phone, display_name, marketing_consent) values ('"
                    + CLIENT + "', '" + TENANT + "', '+420777000001', 'Jana', false)");
            visit(c, MOVED, "CONFIRMED", "2026-09-02 12:00:00", "2026-08-20 09:00:00", "CONSOLE");
            visit(c, SILENT, "COMPLETED", "2026-09-05 08:00:00", "2026-08-21 10:30:00", "SEED");
            visit(c, NO_CREATED, "CANCELLED_BY_CLIENT", "2026-09-06 08:00:00", "2026-08-22 11:00:00", "PUBLIC");
            visit(c, ALREADY_V2, "PENDING", "2026-09-07 08:00:00", "2026-08-23 11:00:00", "CONSOLE");
            event(c, MOVED, "CREATED", "{\"status\":\"PENDING\"}", "2026-08-20 09:00:00");
            event(c, MOVED, "CONFIRMED", "{\"from\":\"PENDING\",\"to\":\"CONFIRMED\"}", "2026-08-20 09:05:00");
            event(c, MOVED, "RESCHEDULED", "{\"from\":\"2026-09-01T10:00:00Z\",\"to\":\"2026-09-01T15:00:00Z\"}", "2026-08-21 09:00:00");
            event(c, MOVED, "RESCHEDULED", "{\"from\":\"2026-09-01T15:00:00Z\",\"to\":\"2026-09-02T12:00:00Z\"}", "2026-08-22 09:00:00");
            event(c, NO_CREATED, "CANCELLED", "{\"from\":\"CONFIRMED\",\"to\":\"CANCELLED_BY_CLIENT\"}", "2026-08-23 09:00:00");
            event(c, ALREADY_V2, "CREATED", "{\"v\":2,\"status\":\"PENDING\",\"marker\":\"kept\"}", "2026-08-23 11:00:00");
            exec(c, "insert into growth_signal (id, tenant_id, signal_key, title, evidence, suggested_action, created_at)"
                    + " values (gen_random_uuid(), '" + TENANT + "', 'NO_SHOW_SPIKE', 't', 'e', 'a', now())");
        }
    }

    private static void visit(Connection c, UUID id, String status, String start, String createdAt, String source)
            throws SQLException {
        exec(c, "insert into appointment (id, tenant_id, specialist_id, service_id, client_id, status, service_start,"
                + " service_end, occupied_start, occupied_end, service_name_snapshot, price_snapshot, duration_snapshot,"
                + " buffer_before_snapshot, buffer_after_snapshot, currency_code, discount_amount, source, created_at,"
                + " updated_at) values ('" + id + "', '" + TENANT + "', '" + MASTER + "', '" + SERVICE + "', '" + CLIENT
                + "', '" + status + "', timestamp '" + start + "', timestamp '" + start + "' + interval '45 minutes',"
                + " timestamp '" + start + "', timestamp '" + start + "' + interval '45 minutes', 'Cut', 90000, 45, 0, 0,"
                + " 'CZK', 1000, '" + source + "', timestamp '" + createdAt + "', timestamp '" + createdAt + "')");
    }

    private static void event(Connection c, UUID appointment, String type, String payload, String at) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("insert into appointment_event (id, tenant_id, appointment_id,"
                + " event_type, payload, created_at) values (gen_random_uuid(), ?, ?, ?, ?, ?::timestamp)")) {
            st.setObject(1, TENANT);
            st.setObject(2, appointment);
            st.setString(3, type);
            st.setString(4, payload);
            st.setString(5, at);
            st.executeUpdate();
        }
    }

    private record Created(JsonNode payload, String createdAt) {
    }

    private List<Created> createdOf(Connection c, UUID appointment) throws Exception {
        List<Created> result = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("select payload, created_at::text from appointment_event"
                + " where appointment_id = ? and event_type = 'CREATED'")) {
            st.setObject(1, appointment);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    result.add(new Created(mapper.readTree(rs.getString(1)), rs.getString(2)));
                }
            }
        }
        return result;
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void v13BackfillsTheJournalAndRebuildsGrowthSignal() throws Exception {
        flyway("12").migrate();
        legacyData();

        flyway(null).migrate();

        try (Connection c = owner()) {
            // A moved visit: v2 from the current row, with the start it was created with (first RESCHEDULED.from).
            Created moved = createdOf(c, MOVED).getFirst();
            JsonNode p = moved.payload();
            assertThat(p.get("v").asInt()).isEqualTo(2);
            assertThat(p.get("backfilled").asBoolean()).isTrue();
            assertThat(p.get("status").asText()).as("status of the old payload").isEqualTo("PENDING");
            assertThat(p.get("serviceStart").asText()).isEqualTo("2026-09-01T10:00:00.000000Z");
            assertThat(p.get("serviceEnd").asText()).isEqualTo("2026-09-01T10:45:00.000000Z");
            assertThat(p.get("specialistId").asText()).isEqualTo(MASTER.toString());
            assertThat(p.get("serviceId").asText()).isEqualTo(SERVICE.toString());
            assertThat(p.get("clientId").asText()).isEqualTo(CLIENT.toString());
            assertThat(p.get("priceMinor").asInt()).isEqualTo(90000);
            assertThat(p.get("discountMinor").asInt()).isEqualTo(1000);
            assertThat(p.get("durationMinutes").asInt()).isEqualTo(45);
            assertThat(p.get("currency").asText()).isEqualTo("CZK");
            assertThat(p.get("serviceName").asText()).isEqualTo("Cut");
            assertThat(p.get("source").asText()).isEqualTo("CONSOLE");
            assertThat(moved.createdAt()).as("the event keeps its time").isEqualTo("2026-08-20 09:00:00");

            // A visit without events gets a CREATED at the row's creation, in its current status and start.
            List<Created> silent = createdOf(c, SILENT);
            assertThat(silent).hasSize(1);
            assertThat(silent.getFirst().createdAt()).isEqualTo("2026-08-21 10:30:00");
            assertThat(silent.getFirst().payload().get("status").asText()).isEqualTo("COMPLETED");
            assertThat(silent.getFirst().payload().get("serviceStart").asText()).isEqualTo("2026-09-05T08:00:00.000000Z");
            assertThat(silent.getFirst().payload().get("backfilled").asBoolean()).isTrue();

            // A visit with status events but no CREATED: CREATED in the status its first event moved it from.
            List<Created> noCreated = createdOf(c, NO_CREATED);
            assertThat(noCreated).hasSize(1);
            assertThat(noCreated.getFirst().payload().get("status").asText()).isEqualTo("CONFIRMED");
            assertThat(noCreated.getFirst().createdAt()).isEqualTo("2026-08-22 11:00:00");

            // An existing v2 payload is left alone.
            assertThat(createdOf(c, ALREADY_V2).getFirst().payload().get("marker").asText()).isEqualTo("kept");
            assertThat(count(c, "select count(*) from appointment_event where event_type = 'CREATED'")).isEqualTo(4);
            assertThat(count(c, "select count(*) from appointment_event where event_type = 'CREATED'"
                    + " and (payload::json ->> 'v') is distinct from '2'")).isZero();

            // growth_signal: plain table, PK (id), unique (tenant_id, dedupe_key), RLS + policy, runtime grants.
            assertThat(count(c, "select count(*) from timescaledb_information.hypertables where hypertable_name = 'growth_signal'"))
                    .isZero();
            assertThat(count(c, "select count(*) from timescaledb_information.hypertables where hypertable_name = 'appointment_event'"))
                    .as("the journal stays a hypertable").isEqualTo(1);
            assertThat(count(c, "select count(*) from growth_signal")).as("old rows are not carried over").isZero();
            assertThat(count(c, """
                    select count(*) from pg_constraint
                    where conrelid = 'growth_signal'::regclass and contype = 'u' and conname = 'growth_signal_dedupe_uq'
                      and pg_get_constraintdef(oid) = 'UNIQUE (tenant_id, dedupe_key)'""")).isEqualTo(1);
            assertThat(count(c, """
                    select count(*) from pg_constraint
                    where conrelid = 'growth_signal'::regclass and contype = 'p' and pg_get_constraintdef(oid) = 'PRIMARY KEY (id)'"""))
                    .isEqualTo(1);
            assertThat(count(c, "select count(*) from pg_class where relname = 'growth_signal' and relrowsecurity")).isEqualTo(1);
            assertThat(count(c, "select count(*) from pg_policies where tablename = 'growth_signal' and policyname = 'tenant_isolation'"))
                    .isEqualTo(1);
            assertThat(count(c, "select count(*) where has_table_privilege('cadence_app', 'growth_signal', 'INSERT')"
                    + " and has_table_privilege('cadence_app', 'growth_signal', 'UPDATE')"
                    + " and has_table_privilege('cadence_app', 'growth_signal', 'SELECT')")).isEqualTo(1);
            assertThat(count(c, "select count(*) from pg_indexes where indexname = 'appointment_event_tenant_appt_idx'"))
                    .isEqualTo(1);

            // The dedupe key holds per tenant.
            exec(c, "insert into growth_signal (id, tenant_id, dedupe_key, signal_key, period, title, evidence,"
                    + " suggested_action, created_at, updated_at) values (gen_random_uuid(), '" + TENANT
                    + "', 'NO_SHOW_SPIKE:tenant:2026-09', 'NO_SHOW_SPIKE', '2026-09', 't', 'e', 'a', now(), now())");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> exec(c, "insert into growth_signal (id, tenant_id,"
                            + " dedupe_key, signal_key, period, title, evidence, suggested_action, created_at, updated_at)"
                            + " values (gen_random_uuid(), '" + TENANT + "', 'NO_SHOW_SPIKE:tenant:2026-09', 'NO_SHOW_SPIKE',"
                            + " '2026-09', 't', 'e', 'a', now(), now())"))
                    .hasMessageContaining("growth_signal_dedupe_uq");
        }
    }
}
