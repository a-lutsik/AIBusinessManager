-- Booking access tokens are stored only as SHA-256 hashes (booking_access_token.token_hash). Before this
-- migration the booking flow also wrote the raw token into owner_task as a "/b/<appointment-uuid>?token=<token>"
-- link, inside body and copy_text and as the whole link column. Replace those occurrences with the
-- {manageLink} placeholder the console fills from POST /api/app/appointments/{id}/access-link.
--
-- Runs as the owner role, to which the tenant_isolation RLS policies do not apply, so every tenant is scrubbed.
-- Tokens are deliberately NOT revoked: clients already hold the same links and they must keep working.
--
-- Token alphabet: Base64 URL-safe without padding (BookingTokens.raw), i.e. [A-Za-z0-9_-].
-- Other places that could hold a raw token were checked and need no scrub: outbox_event.payload,
-- audit_log.before_state/after_state and appointment_event.payload only ever receive {"status":...} JSON,
-- notification_delivery.payload is copied from outbox payloads, and ai_prompt_log is built from the owner's chat
-- and aggregate metrics, never from owner_task text.

UPDATE owner_task
SET body      = regexp_replace(body, '/b/[0-9a-fA-F-]{36}\?token=[A-Za-z0-9_-]+', '{manageLink}', 'g'),
    copy_text = regexp_replace(copy_text, '/b/[0-9a-fA-F-]{36}\?token=[A-Za-z0-9_-]+', '{manageLink}', 'g'),
    link      = CASE WHEN link LIKE '%?token=%' THEN NULL ELSE link END
WHERE body LIKE '%?token=%'
   OR copy_text LIKE '%?token=%'
   OR link LIKE '%?token=%';
