-- Runtime-only Postgres/Timescale features (not fed to jOOQ DDLDatabase).
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

ALTER TABLE tenant ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE outbox_event ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE audit_log ALTER COLUMN id SET DEFAULT gen_random_uuid();

-- Payloads stay VARCHAR so jOOQ codegen (H2/DDLDatabase) matches runtime inserts.

CREATE INDEX IF NOT EXISTS outbox_event_unprocessed_idx
    ON outbox_event (created_at)
    WHERE processed_at IS NULL;
