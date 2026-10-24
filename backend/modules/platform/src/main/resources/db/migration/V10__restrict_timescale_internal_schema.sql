-- TimescaleDB chunks have RLS disabled and inherit the hypertable grants. In _timescaledb_internal (USAGE for
-- PUBLIC) cadence_app could read every tenant's rows by naming a chunk directly: RlsIsolationIT
-- .directChunkAccessDoesNotBypassRls saw 228 foreign rows on 2.30-pg16.
-- Revoking USAGE on _timescaledb_internal is not an option: the extension needs it in every new session.
-- Instead chunks move to a private schema without USAGE for cadence_app. Verified on 2.30-pg16 with a fresh
-- cadence_app login: select/insert (including creation of a new chunk)/update/delete through the hypertable
-- work and keep the V4 tenant_isolation policies; direct chunk access fails with permission denied.
-- Changing associated_schema_name of an existing hypertable has no public API, so this updates the catalog
-- directly (owner/superuser only). Re-check after ALTER EXTENSION timescaledb UPDATE.
-- The direct UPDATE does not invalidate the hypertable cache of already open backends (they would keep creating
-- chunks in _timescaledb_internal), so set_chunk_time_interval is re-applied with the current value afterwards:
-- it rewrites the dimension catalog row and invalidates the cache in every backend.
-- RULE: every future hypertable must be created with associated_schema_name => 'cadence_chunks'
-- (RlsIsolationIT.allPublicHypertablesAndTheirChunksLiveInPrivateSchema enforces it).

CREATE SCHEMA IF NOT EXISTS cadence_chunks;
REVOKE ALL ON SCHEMA cadence_chunks FROM PUBLIC;

UPDATE _timescaledb_catalog.hypertable
SET associated_schema_name = 'cadence_chunks'
WHERE schema_name = 'public';

DO $$
DECLARE
    ht regclass;
    chunk regclass;
    dim record;
BEGIN
    FOR ht IN
        SELECT format('%I.%I', hypertable_schema, hypertable_name)::regclass
        FROM timescaledb_information.hypertables
        WHERE hypertable_schema = 'public'
    LOOP
        FOR chunk IN SELECT show_chunks(ht) LOOP
            IF (SELECT relnamespace FROM pg_class WHERE oid = chunk) <> 'cadence_chunks'::regnamespace THEN
                EXECUTE format('ALTER TABLE %s SET SCHEMA cadence_chunks', chunk);
            END IF;
        END LOOP;
    END LOOP;

    -- Invalidate the hypertable cache in other backends (no-op rewrite of the current time interval).
    FOR dim IN
        SELECT format('%I.%I', hypertable_schema, hypertable_name)::regclass AS ht,
               column_name, time_interval, integer_interval
        FROM timescaledb_information.dimensions
        WHERE hypertable_schema = 'public' AND dimension_type = 'Time'
    LOOP
        IF dim.time_interval IS NOT NULL THEN
            PERFORM set_chunk_time_interval(dim.ht, dim.time_interval, dimension_name => dim.column_name);
        ELSE
            PERFORM set_chunk_time_interval(dim.ht, dim.integer_interval, dimension_name => dim.column_name);
        END IF;
    END LOOP;
END $$;
