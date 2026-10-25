-- Extra invariants of the application database (TimescaleDB, Flyway, RLS). Same use as manifest-tables.sql.
SELECT 'flyway_version|' || version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;
SELECT 'flyway_applied|' || count(*) FROM flyway_schema_history WHERE success;
SELECT 'timescaledb_version|' || extversion FROM pg_extension WHERE extname = 'timescaledb';
SELECT 'hypertables|' || count(*) FROM timescaledb_information.hypertables WHERE hypertable_schema = 'public';
SELECT 'chunks|' || count(*) FROM timescaledb_information.chunks;
SELECT 'chunks_outside_cadence_chunks|' || count(*) FROM timescaledb_information.chunks WHERE chunk_schema <> 'cadence_chunks';
SELECT 'rls_tables|' || count(*)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relrowsecurity;
SELECT 'policies|' || count(*) FROM pg_policies WHERE schemaname = 'public';
SELECT 'job_revoke_chunk_privileges|' || count(*)
FROM timescaledb_information.jobs WHERE proc_schema = 'public' AND proc_name = 'cadence_revoke_chunk_privileges';
