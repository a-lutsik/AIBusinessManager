-- Runtime role for the application and the only sanctioned cross-tenant reads.
-- Runs as the migration/owner role (spring.flyway.user, default "cadence"), which owns every table.
--
-- Why not FORCE ROW LEVEL SECURITY: FORCE would subject the owner to the policies too, and owner-run
-- migrations (backfills, data fixes, the SECURITY DEFINER functions below) must keep seeing all tenants.
-- The hard barrier comes from the application connecting as cadence_app, which is not the owner,
-- has NOBYPASSRLS and therefore is always subject to the tenant_isolation policies from V4.
--
-- The password comes from the Flyway placeholder appDbPassword (APP_DB_PASSWORD, default for local
-- development only). It is substituted textually into a string literal inside a $$ block below, so it must
-- contain neither a single quote nor a dollar sign; the check right after this header rejects such a password
-- before it is used. It is applied whenever this migration runs; rotate it later with
-- ALTER ROLE cadence_app PASSWORD '...'.
-- Creating the role needs CREATEROLE (or superuser) on the migration role.

-- The value is wrapped in its own dollar-quote tag here, so a quote in it cannot end the literal; a quote or a '$'
-- (which could end the $$ block of the role statement) makes the migration fail with a readable message.
-- The spaces around the placeholder are deliberate: Flyway does not substitute a placeholder directly preceded by '$'.
DO $cadence_pw_check$
BEGIN
    IF position(chr(36) in $cadence_pw$ ${appDbPassword} $cadence_pw$) > 0
       OR position(chr(39) in $cadence_pw$ ${appDbPassword} $cadence_pw$) > 0 THEN
        RAISE EXCEPTION 'APP_DB_PASSWORD must not contain a single quote or a dollar sign';
    END IF;
END $cadence_pw_check$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cadence_app') THEN
        CREATE ROLE cadence_app LOGIN PASSWORD '${appDbPassword}'
            NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
    ELSE
        ALTER ROLE cadence_app LOGIN PASSWORD '${appDbPassword}'
            NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END $$;

-- Data access only: no CREATE on the schema, no DDL, no TRUNCATE/REFERENCES/TRIGGER.
GRANT USAGE ON SCHEMA public TO cadence_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cadence_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO cadence_app;
REVOKE ALL ON TABLE flyway_schema_history FROM cadence_app;

-- Tables and sequences created by later migrations (run by this same role) get the same grants.
ALTER DEFAULT PRIVILEGES FOR ROLE CURRENT_USER IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cadence_app;
ALTER DEFAULT PRIVILEGES FOR ROLE CURRENT_USER IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO cadence_app;

-- Hypertables: TimescaleDB propagates GRANT on a hypertable to its existing chunks and copies the
-- hypertable ACL to chunks it creates later. Grant per hypertable and per existing chunk explicitly anyway,
-- so the result does not depend on how "ON ALL TABLES IN SCHEMA" is expanded by the extension.
-- RlsIsolationIT checks INSERT into an existing and a newly created chunk as cadence_app.
DO $$
DECLARE
    ht regclass;
    chunk regclass;
BEGIN
    FOR ht IN
        SELECT format('%I.%I', hypertable_schema, hypertable_name)::regclass
        FROM timescaledb_information.hypertables
        WHERE hypertable_schema = 'public'
    LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %s TO cadence_app', ht);
        FOR chunk IN SELECT show_chunks(ht) LOOP
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %s TO cadence_app', chunk);
        END LOOP;
    END LOOP;
END $$;

-- Public booking links carry no tenant: resolve the tenant of a live token across tenants.
-- SECURITY DEFINER runs as the table owner, which RLS does not apply to (no FORCE). Returns only
-- tenant and appointment ids for non-revoked, non-expired tokens; the caller then opens that tenant scope.
-- expires_at is a UTC TIMESTAMP (see Utc.toLocal), hence now() AT TIME ZONE 'UTC'.
CREATE OR REPLACE FUNCTION booking_token_lookup(p_hash text)
    RETURNS TABLE (tenant_id uuid, appointment_id uuid)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT t.tenant_id, t.appointment_id
    FROM booking_access_token t
    WHERE t.token_hash = p_hash
      AND t.revoked_at IS NULL
      AND t.expires_at > (now() AT TIME ZONE 'UTC')
$$;

-- Outbox claim across tenants. Row locks (FOR UPDATE SKIP LOCKED) belong to the caller's transaction,
-- so the poller must call this inside the transaction that later marks the events processed/failed.
-- Events that reached p_max_attempts are no longer claimed (poison events stop blocking the queue).
CREATE OR REPLACE FUNCTION outbox_claim(p_limit int, p_max_attempts int)
    RETURNS SETOF outbox_event
    LANGUAGE sql
    VOLATILE
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT *
    FROM outbox_event
    WHERE processed_at IS NULL
      AND attempts < p_max_attempts
    ORDER BY created_at
    LIMIT p_limit
    FOR UPDATE SKIP LOCKED
$$;

-- Functions are executable by PUBLIC by default; restrict both to the application role.
REVOKE ALL ON FUNCTION booking_token_lookup(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION outbox_claim(int, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION booking_token_lookup(text) TO cadence_app;
GRANT EXECUTE ON FUNCTION outbox_claim(int, int) TO cadence_app;
