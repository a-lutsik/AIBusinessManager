-- Stage 09: Telegram channel.
--
-- 1. client_channel_link_token: one-time deep-link tokens that bind a client to a messenger chat. Only the SHA-256 hash
--    is stored (as booking_access_token); a used or expired token is dead. Plain table, not a hypertable: the hash must
--    be unique across time, which a hypertable keyed by created_at cannot enforce.
-- 2. channel_link_token_lookup: the one cross-tenant read. The Telegram webhook carries no tenant, so the tenant of a
--    live token is resolved here (SECURITY DEFINER, like booking_token_lookup in V7); the caller then opens that scope.
-- 3. notification_delivery (hypertable, already in cadence_chunks): the delivery journal gets the client and the visit it
--    is about, a status vocabulary (SENT, FAILED, SKIPPED_NO_CHAT, SKIPPED_DISABLED; QUEUED_MANUAL and STUBBED are the
--    values written before this stage) and a per-client index for reading the journal.
-- 4. visit_message_dispatch: the idempotency key of the messages sent about a visit. One row per
--    (visit, message kind, visit start): a repeated run of the reminder job or a replayed outbox event claims the same
--    row and sends nothing; a rescheduled visit has a new start and so gets its reminders again. Plain table for the
--    same reason as 1 (a unique key without the partition column).
-- Undo: DROP TABLE visit_message_dispatch, client_channel_link_token; DROP FUNCTION channel_link_token_lookup(text);
-- ALTER TABLE notification_delivery DROP CONSTRAINT notification_delivery_status_chk, DROP COLUMN client_id,
-- DROP COLUMN appointment_id.

CREATE TABLE client_channel_link_token (
    id          UUID NOT NULL,
    tenant_id   UUID NOT NULL,
    client_id   UUID NOT NULL,
    channel_id  VARCHAR(32) NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMP NOT NULL,
    used_at     TIMESTAMP,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT client_channel_link_token_pk PRIMARY KEY (id),
    CONSTRAINT client_channel_link_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT client_channel_link_token_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT client_channel_link_token_client_fk FOREIGN KEY (client_id) REFERENCES client (id)
);

CREATE INDEX client_channel_link_token_client_idx ON client_channel_link_token (tenant_id, client_id);

ALTER TABLE client_channel_link_token ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON client_channel_link_token
    USING (tenant_id::text = NULLIF(current_setting('app.current_tenant_id', true), ''))
    WITH CHECK (tenant_id::text = NULLIF(current_setting('app.current_tenant_id', true), ''));

CREATE OR REPLACE FUNCTION channel_link_token_lookup(p_hash text, p_channel text)
    RETURNS TABLE (tenant_id uuid, client_id uuid)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT t.tenant_id, t.client_id
    FROM client_channel_link_token t
    WHERE t.token_hash = p_hash
      AND t.channel_id = p_channel
      AND t.used_at IS NULL
      AND t.expires_at > (now() AT TIME ZONE 'UTC')
$$;

REVOKE ALL ON FUNCTION channel_link_token_lookup(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION channel_link_token_lookup(text, text) TO cadence_app;

ALTER TABLE notification_delivery ADD COLUMN client_id UUID;
ALTER TABLE notification_delivery ADD COLUMN appointment_id UUID;
ALTER TABLE notification_delivery ADD CONSTRAINT notification_delivery_status_chk
    CHECK (status IN ('SENT', 'FAILED', 'SKIPPED_NO_CHAT', 'SKIPPED_DISABLED', 'QUEUED_MANUAL', 'STUBBED'));
CREATE INDEX notification_delivery_client_idx ON notification_delivery (tenant_id, client_id, created_at DESC);

CREATE TABLE visit_message_dispatch (
    tenant_id       UUID NOT NULL,
    appointment_id  UUID NOT NULL,
    kind            VARCHAR(32) NOT NULL,
    service_start   TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT visit_message_dispatch_pk PRIMARY KEY (appointment_id, kind, service_start),
    CONSTRAINT visit_message_dispatch_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT visit_message_dispatch_appt_fk FOREIGN KEY (appointment_id) REFERENCES appointment (id)
);

ALTER TABLE visit_message_dispatch ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON visit_message_dispatch
    USING (tenant_id::text = NULLIF(current_setting('app.current_tenant_id', true), ''))
    WITH CHECK (tenant_id::text = NULLIF(current_setting('app.current_tenant_id', true), ''));
