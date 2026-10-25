-- ADR 0001, review decision m4 (2026-10-26): signals resolve themselves. When a rule no longer fires in the current
-- period, that period's signal row gets resolved_at and leaves the open signals; firing again later in the period
-- clears it (dismissed_at is never touched by a recalculation). Rows are kept as history. RLS and the
-- tenant_isolation policy of V13 already cover the table.
ALTER TABLE growth_signal ADD COLUMN resolved_at TIMESTAMP NULL;
