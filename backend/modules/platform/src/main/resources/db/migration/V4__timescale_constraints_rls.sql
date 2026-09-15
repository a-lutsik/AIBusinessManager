-- Runtime Postgres / Timescale features (not parsed by jOOQ DDLDatabase).

CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE appointment
    ADD COLUMN IF NOT EXISTS service_range tstzrange
        GENERATED ALWAYS AS (tstzrange(service_start AT TIME ZONE 'UTC', service_end AT TIME ZONE 'UTC', '[)')) STORED;

ALTER TABLE appointment
    ADD COLUMN IF NOT EXISTS occupied_range tstzrange
        GENERATED ALWAYS AS (tstzrange(occupied_start AT TIME ZONE 'UTC', occupied_end AT TIME ZONE 'UTC', '[)')) STORED;

ALTER TABLE appointment
    ADD CONSTRAINT no_overlapping_active_appointments
    EXCLUDE USING gist (specialist_id WITH =, occupied_range WITH &&)
    WHERE (status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'NO_SHOW'));

SELECT create_hypertable('audit_log', by_range('created_at'), if_not_exists => TRUE);
SELECT create_hypertable('appointment_event', by_range('created_at'), if_not_exists => TRUE);
SELECT create_hypertable('notification_delivery', by_range('created_at'), if_not_exists => TRUE);
SELECT create_hypertable('metric_snapshot', by_range('created_at'), if_not_exists => TRUE);
SELECT create_hypertable('ai_prompt_log', by_range('created_at'), if_not_exists => TRUE);
SELECT create_hypertable('growth_signal', by_range('created_at'), if_not_exists => TRUE);

-- RLS hard barrier. Table owner (migration role) bypasses unless FORCE.
-- Application queries still always add tenant_id via TenantAwareDsl.

ALTER TABLE catalog_service ENABLE ROW LEVEL SECURITY;
ALTER TABLE specialist ENABLE ROW LEVEL SECURITY;
ALTER TABLE master_service ENABLE ROW LEVEL SECURITY;
ALTER TABLE weekly_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE schedule_exception ENABLE ROW LEVEL SECURITY;
ALTER TABLE client ENABLE ROW LEVEL SECURITY;
ALTER TABLE appointment ENABLE ROW LEVEL SECURITY;
ALTER TABLE appointment_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_access_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE owner_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_package ENABLE ROW LEVEL SECURITY;
ALTER TABLE package_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE metric_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE trust_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_prompt_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE growth_signal ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'catalog_service', 'specialist', 'master_service', 'weekly_schedule', 'schedule_exception',
        'client', 'appointment', 'appointment_event', 'booking_access_token', 'owner_task',
        'booking_rules', 'outbox_event', 'audit_log', 'service_package', 'package_ledger',
        'notification_delivery', 'metric_snapshot', 'trust_state', 'ai_draft', 'ai_prompt_log',
        'growth_signal'
    ]
    LOOP
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I
             USING (tenant_id::text = NULLIF(current_setting(''app.current_tenant_id'', true), ''''))
             WITH CHECK (tenant_id::text = NULLIF(current_setting(''app.current_tenant_id'', true), ''''))',
            tbl
        );
    END LOOP;
END $$;
