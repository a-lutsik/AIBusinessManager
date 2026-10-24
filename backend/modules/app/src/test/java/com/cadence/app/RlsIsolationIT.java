package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hard barrier, checked with plain JDBC as the runtime role {@code cadence_app} against the seeded database
 * (Spring context started only for Flyway + seed). Nothing here goes through the application's own filters.
 */
@SpringBootTest
class RlsIsolationIT extends AbstractTimescaleIT {

    /** Postgres SQLSTATE insufficient_privilege: RLS WITH CHECK violations and missing grants. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private static void bindTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.current_tenant_id', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }

    private static long count(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void runtimeRoleIsNotOwnerAndCannotBypassRls() throws Exception {
        try (Connection c = appConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "select current_user, r.rolbypassrls, r.rolsuper from pg_roles r where r.rolname = current_user")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("cadence_app");
                assertThat(rs.getBoolean(2)).as("rolbypassrls").isFalse();
                assertThat(rs.getBoolean(3)).as("rolsuper").isFalse();
            }
            try (ResultSet rs = st.executeQuery("select tableowner from pg_tables where schemaname = 'public' and tablename = 'client'")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo(OWNER_USER).isNotEqualTo("cadence_app");
            }
            assertThatThrownBy(() -> st.executeQuery("select count(*) from flyway_schema_history"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        }
    }

    @Test
    void withoutTenantContextNoRowsAreVisible() throws Exception {
        try (Connection c = appConnection()) {
            assertThat(count(c, "select count(*) from client")).as("auto-commit, no context").isZero();
            c.setAutoCommit(false);
            assertThat(count(c, "select count(*) from client")).as("transaction, no context").isZero();
            assertThat(count(c, "select count(*) from appointment")).isZero();
            c.rollback();
        }
    }

    @Test
    void tenantContextShowsOnlyThatTenant() throws Exception {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, DemoIds.LUMEN);
            assertThat(count(c, "select count(*) from client")).isGreaterThanOrEqualTo(40);
            assertThat(count(c, "select count(*) from client where tenant_id <> ?", DemoIds.LUMEN)).isZero();
            assertThat(count(c, "select count(*) from client where tenant_id = ?", DemoIds.AURA)).isZero();
            c.rollback();

            bindTenant(c, DemoIds.AURA);
            assertThat(count(c, "select count(*) from client where tenant_id = ?", DemoIds.AURA)).isPositive();
            assertThat(count(c, "select count(*) from client where tenant_id = ?", DemoIds.LUMEN)).isZero();
            c.rollback();
        }
    }

    @Test
    void insertForAnotherTenantViolatesPolicy() throws Exception {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, DemoIds.LUMEN);
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into client (id, tenant_id, normalized_phone, display_name, marketing_consent) values (?, ?, ?, ?, false)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, DemoIds.AURA);
                ps.setString(3, "+420777999999");
                ps.setString(4, "Cross tenant");
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE))
                        .hasMessageContaining("row-level security");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void hypertableInsertWorksUnderContextInExistingAndNewChunks() throws Exception {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, DemoIds.LUMEN);
            // now(): chunk created by the seed (owner-created); far future: a chunk created by cadence_app's insert.
            insertAudit(c, DemoIds.LUMEN, LocalDateTime.now());
            insertAudit(c, DemoIds.LUMEN, LocalDateTime.now().plusYears(5));
            assertThat(count(c, "select count(*) from audit_log where action = 'rls.it'")).isEqualTo(2);
            // Chunk grants of cadence_app are revoked by V11 on purpose (pg_stats leak); the hypertable grant is enough.
            assertThat(count(c, "select count(*) from timescaledb_information.chunks where hypertable_name = 'audit_log'"))
                    .as("existing + newly created audit_log chunks").isGreaterThanOrEqualTo(2);
            c.rollback();
        }
    }

    @Test
    void hypertableInsertForAnotherTenantViolatesPolicy() throws Exception {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, DemoIds.LUMEN);
            assertThatThrownBy(() -> insertAudit(c, DemoIds.AURA, LocalDateTime.now()))
                    .isInstanceOf(SQLException.class)
                    .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
            c.rollback();
        }
    }

    @Test
    void directChunkAccessDoesNotBypassRls() throws Exception {
        try (Connection c = appConnection(); Statement st = c.createStatement()) {
            String chunk;
            try (ResultSet rs = st.executeQuery("""
                    select format('%I.%I', chunk_schema, chunk_name) from timescaledb_information.chunks
                    where hypertable_name = 'audit_log' order by range_end desc limit 1
                    """)) {
                assertThat(rs.next()).as("audit_log has a chunk after seeding").isTrue();
                chunk = rs.getString(1);
            }
            // Either the chunk is not readable at all or RLS applies to it too; it must never show seeded rows.
            try (ResultSet rs = st.executeQuery("select count(*) from " + chunk)) {
                rs.next();
                assertThat(rs.getLong(1)).as("rows visible through %s without tenant context", chunk).isZero();
            } catch (SQLException ex) {
                assertThat(ex.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
            }
        }
    }

    @Test
    void allPublicHypertablesAndTheirChunksLiveInPrivateSchema() throws Exception {
        // Owner connection: cadence_app has no access to the TimescaleDB catalog schemas.
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD)) {
            long hypertables = count(c, "select count(*) from _timescaledb_catalog.hypertable where schema_name = 'public'");
            assertThat(hypertables).as("public hypertables exist (audit_log at least)").isPositive();
            assertThat(count(c, """
                    select count(*) from _timescaledb_catalog.hypertable
                    where schema_name = 'public' and associated_schema_name <> 'cadence_chunks'
                    """)).as("public hypertables whose new chunks would go outside cadence_chunks "
                    + "(create new ones with associated_schema_name => 'cadence_chunks')").isZero();
            assertThat(count(c, """
                    select count(*) from timescaledb_information.chunks
                    where hypertable_schema = 'public' and chunk_schema <> 'cadence_chunks'
                    """)).as("chunks of public hypertables outside cadence_chunks").isZero();
            assertThat(count(c, "select count(*) from timescaledb_information.chunks where hypertable_schema = 'public'"))
                    .as("the chunk check above is not vacuous").isPositive();
        }
    }

    private static final String CHUNK_PRIVILEGES = "SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER";

    /** Public hypertable chunks joined to pg_class (relation OIDs), as the owner. */
    private static final String PUBLIC_CHUNKS = """
            from timescaledb_information.chunks ch
            join pg_namespace n on n.nspname = ch.chunk_schema
            join pg_class rel on rel.relnamespace = n.oid and rel.relname = ch.chunk_name
            where ch.hypertable_schema = 'public'
            """;

    @Test
    void chunkPrivilegesAreRevokedFromRuntimeRoleWhileHypertableAccessKeepsWorking() throws Exception {
        LocalDateTime farFuture = LocalDateTime.now().plusYears(10);
        UUID newChunkRow;
        try (Connection owner = ownerConnection(); Connection app = appConnection()) {
            try {
                // A cadence_app insert into a far-future range creates a new chunk, which gets the copied hypertable ACL.
                app.setAutoCommit(false);
                bindTenant(app, DemoIds.LUMEN);
                newChunkRow = insertAudit(app, DemoIds.LUMEN, farFuture);
                app.commit();

                try (Statement st = owner.createStatement()) {
                    st.execute("call cadence_revoke_chunk_privileges(0, null)");
                }

                long chunks = count(owner, "select count(*) " + PUBLIC_CHUNKS);
                assertThat(chunks).as("public hypertable chunks resolved to pg_class").isGreaterThanOrEqualTo(2);
                assertThat(chunks).as("every chunk of the view resolves (join not lossy)")
                        .isEqualTo(count(owner, "select count(*) from timescaledb_information.chunks where hypertable_schema = 'public'"));
                assertThat(count(owner, "select count(*) " + PUBLIC_CHUNKS
                        + " and has_table_privilege('cadence_app', rel.oid, '" + CHUNK_PRIVILEGES + "')"))
                        .as("chunks of public hypertables with any privilege for cadence_app").isZero();
                assertThat(count(owner, "select count(*) " + PUBLIC_CHUNKS
                        + " and exists (select 1 from aclexplode(rel.relacl) a where a.grantee = 'cadence_app'::regrole)"))
                        .as("chunks with an ACL entry for cadence_app").isZero();

                // select / update / insert (existing + brand new chunk) / delete through the hypertable, RLS still applies.
                app.setAutoCommit(false);
                bindTenant(app, DemoIds.LUMEN);
                assertThat(count(app, "select count(*) from audit_log where id = ?", newChunkRow)).isEqualTo(1);
                assertThat(count(app, "select count(*) from audit_log where action = 'rls.it'"))
                        .as("rows of the other tenants stay invisible").isEqualTo(1);
                try (PreparedStatement ps = app.prepareStatement("update audit_log set actor_id = 'x' where id = ?")) {
                    ps.setObject(1, newChunkRow);
                    assertThat(ps.executeUpdate()).isEqualTo(1);
                }
                insertAudit(app, DemoIds.LUMEN, LocalDateTime.now());
                insertAudit(app, DemoIds.LUMEN, farFuture.plusYears(1));
                assertThat(count(app, "select count(*) from audit_log where action = 'rls.it'")).isEqualTo(3);
                try (PreparedStatement ps = app.prepareStatement("delete from audit_log where action = 'rls.it'")) {
                    assertThat(ps.executeUpdate()).isEqualTo(3);
                }
                app.commit();
            } finally {
                cleanUpTestChunks(owner);
            }
        }
    }

    @Test
    void chunkStatisticsAreNotVisibleToRuntimeRole() throws Exception {
        try (Connection owner = ownerConnection(); Connection app = appConnection(); Statement st = owner.createStatement()) {
            st.execute("call cadence_revoke_chunk_privileges(0, null)");
            java.util.List<String> chunks = new java.util.ArrayList<>();
            try (ResultSet rs = st.executeQuery("select format('%I.%I', ch.chunk_schema, ch.chunk_name) from timescaledb_information.chunks ch where ch.hypertable_schema = 'public'")) {
                while (rs.next()) {
                    chunks.add(rs.getString(1));
                }
            }
            assertThat(chunks).as("seeded chunks to analyze").isNotEmpty();
            for (String chunk : chunks) {
                st.execute("analyze " + chunk);
            }
            assertThat(count(owner, "select count(*) from pg_stats where schemaname = 'cadence_chunks'"))
                    .as("chunk statistics exist (owner view), so the zero below is not vacuous").isPositive();
            assertThat(count(app, "select count(*) from pg_stats where schemaname = 'cadence_chunks'"))
                    .as("pg_stats rows of chunks visible to cadence_app").isZero();
            assertThat(count(app, "select count(*) from pg_stats_ext where schemaname = 'cadence_chunks'"))
                    .as("pg_stats_ext rows of chunks visible to cadence_app").isZero();
            assertThat(count(app, "select count(*) from pg_stats_ext_exprs where schemaname = 'cadence_chunks'"))
                    .as("pg_stats_ext_exprs rows of chunks visible to cadence_app").isZero();
            assertThat(count(app, "select count(*) from pg_stats where tablename like '\\_hyper\\_%'"))
                    .as("pg_stats rows of chunks in any schema visible to cadence_app").isZero();
        }
    }

    @Test
    void chunkPrivilegeRevokeJobIsRegistered() throws Exception {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, """
                    select count(*) from timescaledb_information.jobs
                    where proc_schema = 'public' and proc_name = 'cadence_revoke_chunk_privileges'
                      and schedule_interval = interval '1 minute' and scheduled
                    """)).as("revoke job registered exactly once").isEqualTo(1);
        }
    }

    private static void cleanUpTestChunks(Connection owner) throws SQLException {
        try (Statement st = owner.createStatement()) {
            st.execute("delete from audit_log where action = 'rls.it'");
            st.execute("select drop_chunks('audit_log', older_than => localtimestamp + interval '13 years', newer_than => localtimestamp + interval '9 years')");
        }
    }

    @Test
    void tokenLookupFunctionIsCallableWithoutTenantContext() throws Exception {
        try (Connection c = appConnection()) {
            assertThat(count(c, "select count(*) from booking_token_lookup(?)", "no-such-hash")).isZero();
        }
    }

    private static UUID insertAudit(Connection c, UUID tenant, LocalDateTime createdAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "insert into audit_log (id, tenant_id, actor_type, action, entity_type, created_at) values (?, ?, 'SYSTEM', 'rls.it', 'test', ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setTimestamp(3, Timestamp.valueOf(createdAt));
            ps.executeUpdate();
        }
        return id;
    }
}
