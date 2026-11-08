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
-- 5. Delivery queue: visit_message_dispatch doubles as the queue of messages to send (status PENDING -> SENDING -> DONE),
--    so the Telegram call runs outside any database transaction. visit_message_claim hands rows (the worker asks for one at a time) to the delivery
--    worker across tenants (SECURITY DEFINER, FOR UPDATE SKIP LOCKED, like outbox_claim); a SENDING row older than the
--    cutoff (the process died mid-send) comes back flagged stale and is never sent again, because the answer of
--    Telegram is unknown: it is journaled as FAILED and falls back to the manual task. A row whose preparation fails
--    (attempts, next_attempt_at) goes back with a growing delay and is skipped by the claim until then, so one bad row
--    never blocks the queue; after the last attempt the worker closes it as FAILED.
-- 6. Binding needs proof of the phone: /start <token> only records the chat on the token (chat_id), the chat is stored on
--    the client once the contact the person shares matches the client's phone. channel_link_pending_lookup finds that
--    token by chat, channel_link_token_purge removes used and long expired tokens.
-- 7. visit_message_purge removes handled (DONE) queue rows after the retention period.
-- Undo: DROP TABLE visit_message_dispatch, client_channel_link_token; DROP FUNCTION channel_link_token_lookup(text, text),
-- channel_link_pending_lookup(text, text), channel_link_token_purge(timestamp), visit_message_claim(int, timestamp),
-- visit_message_purge(timestamp);
-- ALTER TABLE notification_delivery DROP CONSTRAINT notification_delivery_status_chk, DROP COLUMN client_id,
-- DROP COLUMN appointment_id.

CREATE TABLE client_channel_link_token (
    id          UUID NOT NULL,
    tenant_id   UUID NOT NULL,
    client_id   UUID NOT NULL,
    channel_id  VARCHAR(32) NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMP NOT NULL,
    chat_id     VARCHAR(64),
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
      AND t.chat_id IS NULL
      AND t.expires_at > (now() AT TIME ZONE 'UTC')
$$;

CREATE OR REPLACE FUNCTION channel_link_pending_lookup(p_chat text, p_channel text)
    RETURNS TABLE (tenant_id uuid, client_id uuid, token_hash text)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT t.tenant_id, t.client_id, t.token_hash::text
    FROM client_channel_link_token t
    WHERE t.chat_id = p_chat
      AND t.channel_id = p_channel
      AND t.used_at IS NULL
      AND t.expires_at > (now() AT TIME ZONE 'UTC')
    ORDER BY t.created_at DESC
    LIMIT 1
$$;

CREATE OR REPLACE FUNCTION channel_link_token_purge(p_before timestamp)
    RETURNS int
    LANGUAGE sql
    VOLATILE
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    WITH gone AS (
        DELETE FROM client_channel_link_token t
        WHERE t.used_at < p_before OR t.expires_at < p_before
        RETURNING 1
    )
    SELECT count(*)::int FROM gone
$$;

REVOKE ALL ON FUNCTION channel_link_token_lookup(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION channel_link_token_lookup(text, text) TO cadence_app;
REVOKE ALL ON FUNCTION channel_link_pending_lookup(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION channel_link_pending_lookup(text, text) TO cadence_app;
REVOKE ALL ON FUNCTION channel_link_token_purge(timestamp) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION channel_link_token_purge(timestamp) TO cadence_app;

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
    client_id       UUID NOT NULL,
    service_name    VARCHAR(256) NOT NULL,
    manual_fallback BOOLEAN NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    claimed_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT visit_message_dispatch_status_chk CHECK (status IN ('PENDING', 'SENDING', 'DONE')),
    CONSTRAINT visit_message_dispatch_pk PRIMARY KEY (appointment_id, kind, service_start),
    CONSTRAINT visit_message_dispatch_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT visit_message_dispatch_appt_fk FOREIGN KEY (appointment_id) REFERENCES appointment (id)
);

ALTER TABLE visit_message_dispatch ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON visit_message_dispatch
    USING (tenant_id::text = NULLIF(current_setting('app.current_tenant_id', true), ''))
    WITH CHECK (tenant_id::text = NULLIF(current_setting('app.current_tenant_id', true), ''));

CREATE INDEX visit_message_dispatch_open_idx ON visit_message_dispatch (created_at) WHERE status <> 'DONE';

CREATE OR REPLACE FUNCTION visit_message_claim(p_limit int, p_stale_before timestamp)
    RETURNS TABLE (tenant_id uuid, appointment_id uuid, kind text, service_start timestamp, client_id uuid,
                   service_name text, manual_fallback boolean, stale boolean, attempts int)
    LANGUAGE sql
    VOLATILE
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    WITH due AS (
        SELECT d.appointment_id, d.kind, d.service_start, (d.status = 'SENDING') AS was_sending
        FROM visit_message_dispatch d
        WHERE (d.status = 'PENDING' AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= (now() AT TIME ZONE 'UTC')))
           OR (d.status = 'SENDING' AND d.claimed_at < p_stale_before)
        ORDER BY d.created_at
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    )
    UPDATE visit_message_dispatch d
    SET status = 'SENDING', claimed_at = (now() AT TIME ZONE 'UTC')
    FROM due
    WHERE d.appointment_id = due.appointment_id AND d.kind = due.kind AND d.service_start = due.service_start
    RETURNING d.tenant_id, d.appointment_id, d.kind::text, d.service_start, d.client_id, d.service_name::text,
              d.manual_fallback, due.was_sending, d.attempts
$$;

REVOKE ALL ON FUNCTION visit_message_claim(int, timestamp) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION visit_message_claim(int, timestamp) TO cadence_app;

CREATE OR REPLACE FUNCTION visit_message_purge(p_before timestamp)
    RETURNS int
    LANGUAGE sql
    VOLATILE
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    WITH gone AS (
        DELETE FROM visit_message_dispatch d
        WHERE d.status = 'DONE' AND d.created_at < p_before
        RETURNING 1
    )
    SELECT count(*)::int FROM gone
$$;

REVOKE ALL ON FUNCTION visit_message_purge(timestamp) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION visit_message_purge(timestamp) TO cadence_app;
