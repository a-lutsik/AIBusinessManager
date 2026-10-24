-- Hide chunk statistics from the runtime role.
-- TimescaleDB chunks have relrowsecurity = false, and pg_stats / pg_stats_ext / pg_stats_ext_exprs decide
-- visibility by column privilege only (not by schema USAGE). While cadence_app holds SELECT on a chunk,
-- `select ... from pg_stats where schemaname = 'cadence_chunks'` returns other tenants' most_common_vals and
-- histogram_bounds (tenant_id, audit_log.after_state, ai_prompt_log.prompt, ...), whatever the private schema
-- of V10 does for direct reads.
-- Fix: cadence_app holds no privilege on any chunk. Reads and writes keep working because TimescaleDB executes
-- them through the hypertable (the only place where the V4 tenant_isolation policies and the grants apply).
-- This supersedes the per-chunk grants of V7, which are now revoked again.
--
-- TimescaleDB copies the hypertable ACL onto every chunk it creates later, so the revoke must be repeated:
-- cadence_revoke_chunk_privileges() is idempotent and cheap (it only touches chunks that still carry an ACL entry
-- for cadence_app) and runs as a TimescaleDB job every minute. Exposure window: a new chunk keeps the copied
-- grants until the next job run. Direct reads of it are already blocked by the private schema (V10), so the
-- window only matters for pg_stats, and only after an ANALYZE/autovacuum has produced statistics for the chunk.
-- The job is owned by the migration role (it needs ownership of the chunks to revoke); the signature
-- (job_id int, config jsonb) is the one TimescaleDB requires for job procedures.

CREATE OR REPLACE PROCEDURE cadence_revoke_chunk_privileges(job_id int, config jsonb)
    LANGUAGE plpgsql
AS $$
DECLARE
    ht regclass;
    chunk regclass;
BEGIN
    FOR ht IN
        SELECT format('%I.%I', hypertable_schema, hypertable_name)::regclass
        FROM timescaledb_information.hypertables
        WHERE hypertable_schema = 'public'
    LOOP
        FOR chunk IN SELECT show_chunks(ht) LOOP
            IF EXISTS (
                SELECT 1
                FROM pg_class c, aclexplode(c.relacl) acl
                WHERE c.oid = chunk
                  AND acl.grantee = 'cadence_app'::regrole
            ) THEN
                EXECUTE format('REVOKE ALL ON TABLE %s FROM cadence_app', chunk);
            END IF;
        END LOOP;
    END LOOP;
END $$;

REVOKE ALL ON PROCEDURE cadence_revoke_chunk_privileges(int, jsonb) FROM PUBLIC;

-- Existing chunks.
CALL cadence_revoke_chunk_privileges(0, NULL);

-- Chunks created later. add_job has no if_not_exists, so check first (migration is re-runnable after a repair).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM timescaledb_information.jobs
        WHERE proc_schema = 'public' AND proc_name = 'cadence_revoke_chunk_privileges'
    ) THEN
        PERFORM add_job('cadence_revoke_chunk_privileges', schedule_interval => INTERVAL '1 minute');
    END IF;
END $$;
