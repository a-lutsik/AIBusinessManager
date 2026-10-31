-- ADR 0002 (stage 05): AI manager with a tool loop, pseudonyms in the database, drafts with confirmation, budget.
--
-- 1. ai_tenant_settings: owner toggle "client notes go to the LLM (masked)", default off; monthly token budget set by
--    the operator (NULL = app.ai.default-monthly-token-budget).
-- 2. ai_conversation / ai_message / ai_pseudonym: dialogue history (masked texts only) and the per-conversation map
--    of opaque refs (CLIENT_n, MASTER_n, VISIT_n, SERVICE_n, DRAFT_n) back to entity ids and display names.
--    Child rows reference (tenant_id, conversation_id), so a row can only hang off a conversation of its own tenant
--    (FK checks are not subject to RLS), and are deleted with the conversation (retention job, ADR 0002 §6).
--    entity_id is NOT NULL (every kind, DRAFT included, names an existing row), so the unique key
--    (tenant_id, conversation_id, kind, entity_id) really makes "same entity -> same token".
-- 3. ai_usage_month: tokens and calls per tenant and month (period 'YYYY-MM' in the tenant zone), atomic upserts.
-- 4. ai_draft: lifecycle columns; existing PENDING drafts get the 24 h TTL from their creation; drafts reference their
--    conversation like messages and pseudonyms (cascade), legacy conversation ids are back-filled as conversations.
-- 5. ai_prompt_log: per-call metadata; prompt/response widened to the full masked request/response (65536).
--    ALTER COLUMN TYPE on the hypertable: varchar widening is binary-coercible, no rewrite, propagated to the chunks.
-- 6. ai_tool_call: new hypertable, chunks in cadence_chunks (V10 rule; V11 job revokes chunk grants).
--    Retention: 30 days on ai_prompt_log and ai_tool_call (TimescaleDB policy, owned by the migration role).
-- 7. RLS + tenant_isolation policy on every new table, as in V4/V13. No FORCE (see V7): the runtime role cadence_app
--    is not the owner and has NOBYPASSRLS. Grants come from the V7 default privileges (same owner role runs this).

-- 1.
CREATE TABLE ai_tenant_settings (
    tenant_id               UUID NOT NULL,
    notes_to_llm            BOOLEAN NOT NULL DEFAULT false,
    monthly_token_budget    BIGINT NULL,
    updated_at              TIMESTAMP NOT NULL,
    CONSTRAINT ai_tenant_settings_pk PRIMARY KEY (tenant_id),
    CONSTRAINT ai_tenant_settings_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT ai_tenant_settings_budget_ck CHECK (monthly_token_budget IS NULL OR monthly_token_budget >= 0)
);

-- 2.
CREATE TABLE ai_conversation (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    locale              VARCHAR(8) NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    last_message_at     TIMESTAMP NOT NULL,
    CONSTRAINT ai_conversation_pk PRIMARY KEY (id),
    CONSTRAINT ai_conversation_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT ai_conversation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE INDEX ai_conversation_tenant_last_idx ON ai_conversation (tenant_id, last_message_at);

CREATE TABLE ai_message (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    conversation_id     UUID NOT NULL,
    role                VARCHAR(16) NOT NULL,
    content             VARCHAR(16384) NOT NULL,
    provider            VARCHAR(64) NULL,
    model               VARCHAR(128) NULL,
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT ai_message_pk PRIMARY KEY (id),
    CONSTRAINT ai_message_conversation_fk FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES ai_conversation (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ai_message_role_ck CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX ai_message_conversation_idx ON ai_message (tenant_id, conversation_id, created_at);

CREATE TABLE ai_pseudonym (
    tenant_id           UUID NOT NULL,
    conversation_id     UUID NOT NULL,
    kind                VARCHAR(16) NOT NULL,
    token_no            INT NOT NULL,
    token               VARCHAR(32) NOT NULL,
    entity_id           UUID NOT NULL,
    display             VARCHAR(512) NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT ai_pseudonym_pk PRIMARY KEY (tenant_id, conversation_id, token),
    CONSTRAINT ai_pseudonym_entity_uq UNIQUE (tenant_id, conversation_id, kind, entity_id),
    CONSTRAINT ai_pseudonym_conversation_fk FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES ai_conversation (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ai_pseudonym_kind_ck CHECK (kind IN ('CLIENT', 'MASTER', 'VISIT', 'SERVICE', 'DRAFT')),
    CONSTRAINT ai_pseudonym_token_no_ck CHECK (token_no >= 1)
);

-- 3.
CREATE TABLE ai_usage_month (
    tenant_id           UUID NOT NULL,
    period              CHAR(7) NOT NULL,
    prompt_tokens       BIGINT NOT NULL DEFAULT 0,
    completion_tokens   BIGINT NOT NULL DEFAULT 0,
    llm_calls           INT NOT NULL DEFAULT 0,
    tool_calls          INT NOT NULL DEFAULT 0,
    exceeded_at         TIMESTAMP NULL,
    updated_at          TIMESTAMP NOT NULL,
    CONSTRAINT ai_usage_month_pk PRIMARY KEY (tenant_id, period),
    CONSTRAINT ai_usage_month_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

-- 4.
ALTER TABLE ai_draft ADD COLUMN expires_at TIMESTAMP NULL;
ALTER TABLE ai_draft ADD COLUMN resolved_at TIMESTAMP NULL;
ALTER TABLE ai_draft ADD COLUMN applied_entity_id UUID NULL;
ALTER TABLE ai_draft ADD COLUMN failure_code VARCHAR(64) NULL;
ALTER TABLE ai_draft ADD COLUMN failure_message VARCHAR(1024) NULL;
ALTER TABLE ai_draft ADD COLUMN message_id UUID NULL;

UPDATE ai_draft SET expires_at = created_at + INTERVAL '24 hours' WHERE status = 'PENDING' AND expires_at IS NULL;

CREATE INDEX ai_draft_tenant_status_idx ON ai_draft (tenant_id, status, created_at);

-- Drafts belong to their conversation and go with it (ADR 0002 §6; the chain stays in audit_log, TD-44). Drafts written
-- before V15 name a conversation id that never had a row: give each such id a conversation of the draft's tenant first.
-- A conversation id shared by drafts of two tenants cannot become one conversation row: fail with a clear message
-- instead of a duplicate key (resolve such rows by hand, then re-run).
DO $$
DECLARE
    shared text;
BEGIN
    SELECT string_agg(conversation_id::text, ', ') INTO shared
    FROM (
        SELECT conversation_id FROM ai_draft GROUP BY conversation_id HAVING count(DISTINCT tenant_id) > 1
    ) s;
    IF shared IS NOT NULL THEN
        RAISE EXCEPTION 'V15: ai_draft.conversation_id used by more than one tenant, cannot back-fill ai_conversation: %', shared;
    END IF;
END $$;

INSERT INTO ai_conversation (id, tenant_id, locale, created_at, last_message_at)
SELECT d.conversation_id, d.tenant_id, 'en', min(d.created_at), max(d.created_at)
FROM ai_draft d
WHERE NOT EXISTS (SELECT 1 FROM ai_conversation c WHERE c.id = d.conversation_id)
GROUP BY d.conversation_id, d.tenant_id;

ALTER TABLE ai_draft ADD CONSTRAINT ai_draft_conversation_fk FOREIGN KEY (tenant_id, conversation_id)
    REFERENCES ai_conversation (tenant_id, id) ON DELETE CASCADE;

-- 5.
ALTER TABLE ai_prompt_log ADD COLUMN message_id UUID NULL;
ALTER TABLE ai_prompt_log ADD COLUMN prompt_tokens INT NULL;
ALTER TABLE ai_prompt_log ADD COLUMN completion_tokens INT NULL;
ALTER TABLE ai_prompt_log ADD COLUMN finish_reason VARCHAR(32) NULL;
ALTER TABLE ai_prompt_log ADD COLUMN latency_ms INT NULL;
ALTER TABLE ai_prompt_log ADD COLUMN tool_calls_json VARCHAR(8192) NULL;
ALTER TABLE ai_prompt_log ALTER COLUMN prompt TYPE VARCHAR(65536);
ALTER TABLE ai_prompt_log ALTER COLUMN response TYPE VARCHAR(65536);

-- 6.
CREATE TABLE ai_tool_call (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    conversation_id     UUID NOT NULL,
    llm_call_id         UUID NULL,
    tool_name           VARCHAR(64) NOT NULL,
    arguments           VARCHAR(4096) NOT NULL,
    result              VARCHAR(16384) NULL,
    status              VARCHAR(16) NOT NULL,
    error_code          VARCHAR(64) NULL,
    duration_ms         INT NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT ai_tool_call_pk PRIMARY KEY (id, created_at),
    CONSTRAINT ai_tool_call_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT ai_tool_call_status_ck CHECK (status IN ('OK', 'ERROR'))
);

CREATE INDEX ai_tool_call_conversation_idx ON ai_tool_call (tenant_id, conversation_id, created_at);

SELECT create_hypertable('ai_tool_call', 'created_at',
                         associated_schema_name => 'cadence_chunks',
                         if_not_exists => TRUE);

SELECT add_retention_policy('ai_prompt_log', INTERVAL '30 days', if_not_exists => TRUE);
SELECT add_retention_policy('ai_tool_call', INTERVAL '30 days', if_not_exists => TRUE);

-- 7.
ALTER TABLE ai_tenant_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_conversation ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_pseudonym ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage_month ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_tool_call ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'ai_tenant_settings', 'ai_conversation', 'ai_message', 'ai_pseudonym', 'ai_usage_month', 'ai_tool_call'
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
