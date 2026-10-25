-- ADR 0001 (stage 04): growth metrics are computed from the booking journal, which becomes self-sufficient.
--
-- 1. Back-fill. Every CREATED event written before this migration carried only {"status": ...}. It is rewritten to
--    the v2 payload from the current appointment row: specialist, service, client, price, discount, duration,
--    currency, service name and source as they are now, "status" from the old payload, "serviceStart" = the earliest
--    RESCHEDULED.from of the visit (the start the visit was created with) or the current service_start when it was
--    never moved, "serviceEnd" = that start + duration, plus "backfilled": true. A visit without a CREATED event gets
--    one, created_at = appointment.created_at, status = the "from" of its first status event or its current status.
--    Known limit: client merges before V13 wrote no event and are not recoverable per visit, so a back-filled CREATED
--    names the current (surviving) client; those merges are invisible in the journal (tech-debt).
-- 2. growth_signal stops being a hypertable: signals are deduplicated per (tenant, dedupe_key) and updated in place,
--    which a hypertable keyed by created_at cannot enforce. Old rows are not carried over (they were duplicates by
--    construction and are recomputed by the next recalculation). RLS and the tenant_isolation policy as in V4; grants
--    come from the V7 default privileges (this migration runs as the same owner role).
-- 3. Index for the per-tenant, per-visit journal read of the fold.

-- 1a. Old CREATED payloads -> v2.
UPDATE appointment_event e
SET payload = json_build_object(
        'v', 2,
        'specialistId', a.specialist_id,
        'serviceId', a.service_id,
        'clientId', a.client_id,
        'status', COALESCE(e.payload::json ->> 'status', a.status),
        'serviceStart', to_char(s.original_start, 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'serviceEnd', to_char(s.original_start + make_interval(mins => a.duration_snapshot), 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'priceMinor', a.price_snapshot,
        'discountMinor', a.discount_amount,
        'durationMinutes', a.duration_snapshot,
        'currency', a.currency_code,
        'serviceName', a.service_name_snapshot,
        'source', a.source,
        'backfilled', true
    )::text
FROM appointment a
CROSS JOIN LATERAL (
    SELECT COALESCE(
        (SELECT ((r.payload::json ->> 'from')::timestamptz AT TIME ZONE 'UTC')
         FROM appointment_event r
         WHERE r.appointment_id = a.id
           AND r.tenant_id = a.tenant_id
           AND r.event_type = 'RESCHEDULED'
         ORDER BY r.created_at, r.id
         LIMIT 1),
        a.service_start
    ) AS original_start
) s
WHERE e.event_type = 'CREATED'
  AND e.appointment_id = a.id
  AND e.tenant_id = a.tenant_id
  AND (e.payload IS NULL OR (e.payload::json ->> 'v') IS DISTINCT FROM '2');

-- 1b. Visits without a CREATED event.
INSERT INTO appointment_event (id, tenant_id, appointment_id, event_type, payload, created_at)
SELECT gen_random_uuid(),
       a.tenant_id,
       a.id,
       'CREATED',
       json_build_object(
           'v', 2,
           'specialistId', a.specialist_id,
           'serviceId', a.service_id,
           'clientId', a.client_id,
           'status', COALESCE(first_status.from_status, a.status),
           'serviceStart', to_char(s.original_start, 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
           'serviceEnd', to_char(s.original_start + make_interval(mins => a.duration_snapshot), 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
           'priceMinor', a.price_snapshot,
           'discountMinor', a.discount_amount,
           'durationMinutes', a.duration_snapshot,
           'currency', a.currency_code,
           'serviceName', a.service_name_snapshot,
           'source', a.source,
           'backfilled', true
       )::text,
       a.created_at
FROM appointment a
CROSS JOIN LATERAL (
    SELECT COALESCE(
        (SELECT ((r.payload::json ->> 'from')::timestamptz AT TIME ZONE 'UTC')
         FROM appointment_event r
         WHERE r.appointment_id = a.id
           AND r.tenant_id = a.tenant_id
           AND r.event_type = 'RESCHEDULED'
         ORDER BY r.created_at, r.id
         LIMIT 1),
        a.service_start
    ) AS original_start
) s
LEFT JOIN LATERAL (
    SELECT r.payload::json ->> 'from' AS from_status
    FROM appointment_event r
    WHERE r.appointment_id = a.id
      AND r.tenant_id = a.tenant_id
      AND r.event_type IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED')
    ORDER BY r.created_at, r.id
    LIMIT 1
) first_status ON true
WHERE NOT EXISTS (
    SELECT 1 FROM appointment_event c
    WHERE c.appointment_id = a.id
      AND c.tenant_id = a.tenant_id
      AND c.event_type = 'CREATED'
);

-- 2. growth_signal as a plain table with a dedupe key.
DROP TABLE growth_signal;

CREATE TABLE growth_signal (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    dedupe_key          VARCHAR(256) NOT NULL,
    signal_key          VARCHAR(64) NOT NULL,
    specialist_id       UUID NULL,
    period              VARCHAR(7) NOT NULL,
    title               VARCHAR(256) NOT NULL,
    evidence            VARCHAR(1024) NOT NULL,
    suggested_action    VARCHAR(1024) NOT NULL,
    severity            VARCHAR(16) NOT NULL DEFAULT 'INFO',
    action_type         VARCHAR(64),
    action_payload      VARCHAR(2048),
    dismissed_at        TIMESTAMP,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    CONSTRAINT growth_signal_pk PRIMARY KEY (id),
    CONSTRAINT growth_signal_dedupe_uq UNIQUE (tenant_id, dedupe_key),
    CONSTRAINT growth_signal_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

ALTER TABLE growth_signal ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON growth_signal
    USING (tenant_id::text = NULLIF(current_setting('app.current_tenant_id', true), ''))
    WITH CHECK (tenant_id::text = NULLIF(current_setting('app.current_tenant_id', true), ''));

-- 3. Journal read of the fold: one tenant, visits in order.
CREATE INDEX appointment_event_tenant_appt_idx ON appointment_event (tenant_id, appointment_id, created_at);
