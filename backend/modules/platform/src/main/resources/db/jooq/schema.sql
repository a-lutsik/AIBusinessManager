-- Platform baseline for Flyway + jOOQ DDLDatabase (H2 simulation).
-- Demo tenant for later seed: Lumen Studio (Asia/Tbilisi, GEL).
-- RLS: SET LOCAL app.current_tenant_id; soft barrier = TenantAwareDsl; hard barrier = RLS.

CREATE TABLE tenant (
    id              UUID NOT NULL,
    slug            VARCHAR(128) NOT NULL,
    display_name    VARCHAR(256) NOT NULL,
    timezone        VARCHAR(64) NOT NULL,
    currency_code   CHAR(3) NOT NULL,
    country_code    CHAR(2) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenant_pk PRIMARY KEY (id),
    CONSTRAINT tenant_slug_uq UNIQUE (slug)
);

CREATE TABLE outbox_event (
    id              UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    type            VARCHAR(128) NOT NULL,
    aggregate_type  VARCHAR(128) NOT NULL,
    aggregate_id    UUID NOT NULL,
    payload         VARCHAR(8192) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at    TIMESTAMP,
    attempts        INT NOT NULL DEFAULT 0,
    last_error      VARCHAR(2048),
    CONSTRAINT outbox_event_pk PRIMARY KEY (id),
    CONSTRAINT outbox_event_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE INDEX outbox_event_created_at_idx ON outbox_event (created_at);

CREATE TABLE audit_log (
    id              UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    actor_id        VARCHAR(128),
    actor_type      VARCHAR(32) NOT NULL,
    action          VARCHAR(128) NOT NULL,
    entity_type     VARCHAR(128) NOT NULL,
    entity_id       UUID,
    before_state    VARCHAR(8192),
    after_state     VARCHAR(8192),
    source          VARCHAR(64),
    request_id      VARCHAR(64),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT audit_log_pk PRIMARY KEY (id, created_at)
);
-- Operating schema for Booking Core / CRM / later layers.
-- Portable types only (jOOQ DDLDatabase). Timescale/GiST/RLS in V4.

CREATE TABLE booking_rules (
    tenant_id                         UUID NOT NULL,
    slot_step_minutes                 INT NOT NULL,
    min_notice_minutes                INT NOT NULL,
    horizon_days                      INT NOT NULL,
    client_reschedule_allowed         BOOLEAN NOT NULL,
    late_cancellation_hours           INT NOT NULL,
    new_client_requires_confirmation  BOOLEAN NOT NULL,
    deduct_package_on_no_show         BOOLEAN NOT NULL,
    week_starts_on                    INT NOT NULL,
    time_format_24h                   BOOLEAN NOT NULL,
    CONSTRAINT booking_rules_pk PRIMARY KEY (tenant_id),
    CONSTRAINT booking_rules_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE catalog_service (
    id                      UUID NOT NULL,
    tenant_id               UUID NOT NULL,
    name                    VARCHAR(256) NOT NULL,
    description             VARCHAR(2048),
    duration_minutes        INT NOT NULL,
    price_minor             INT NOT NULL,
    buffer_before_minutes   INT NOT NULL,
    buffer_after_minutes    INT NOT NULL,
    color                   VARCHAR(16) NOT NULL,
    active                  BOOLEAN NOT NULL,
    public_visible          BOOLEAN NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT catalog_service_pk PRIMARY KEY (id),
    CONSTRAINT catalog_service_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE INDEX catalog_service_tenant_idx ON catalog_service (tenant_id);

CREATE TABLE specialist (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    display_name        VARCHAR(256) NOT NULL,
    keycloak_user_id    VARCHAR(128),
    calendar_color      VARCHAR(16) NOT NULL,
    active              BOOLEAN NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT specialist_pk PRIMARY KEY (id),
    CONSTRAINT specialist_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE INDEX specialist_tenant_idx ON specialist (tenant_id);

CREATE TABLE master_service (
    id                              UUID NOT NULL,
    tenant_id                       UUID NOT NULL,
    specialist_id                   UUID NOT NULL,
    service_id                      UUID NOT NULL,
    offered                         BOOLEAN NOT NULL,
    duration_minutes_override       INT,
    price_minor_override            INT,
    buffer_before_minutes_override  INT,
    buffer_after_minutes_override   INT,
    CONSTRAINT master_service_pk PRIMARY KEY (id),
    CONSTRAINT master_service_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT master_service_specialist_fk FOREIGN KEY (specialist_id) REFERENCES specialist (id),
    CONSTRAINT master_service_service_fk FOREIGN KEY (service_id) REFERENCES catalog_service (id),
    CONSTRAINT master_service_uq UNIQUE (tenant_id, specialist_id, service_id)
);

CREATE TABLE weekly_schedule (
    id              UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    specialist_id   UUID NOT NULL,
    weekday         INT NOT NULL,
    start_minute    INT NOT NULL,
    end_minute      INT NOT NULL,
    CONSTRAINT weekly_schedule_pk PRIMARY KEY (id),
    CONSTRAINT weekly_schedule_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT weekly_schedule_specialist_fk FOREIGN KEY (specialist_id) REFERENCES specialist (id)
);

CREATE INDEX weekly_schedule_specialist_idx ON weekly_schedule (specialist_id);

CREATE TABLE schedule_exception (
    id              UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    specialist_id   UUID NOT NULL,
    exception_date  DATE NOT NULL,
    kind            VARCHAR(32) NOT NULL,
    start_minute    INT,
    end_minute      INT,
    note            VARCHAR(512),
    CONSTRAINT schedule_exception_pk PRIMARY KEY (id),
    CONSTRAINT schedule_exception_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT schedule_exception_specialist_fk FOREIGN KEY (specialist_id) REFERENCES specialist (id)
);

CREATE TABLE client (
    id                      UUID NOT NULL,
    tenant_id               UUID NOT NULL,
    normalized_phone        VARCHAR(32) NOT NULL,
    display_name            VARCHAR(256) NOT NULL,
    notes                   VARCHAR(4096),
    tags                    VARCHAR(1024),
    preferred_locale        VARCHAR(8),
    telegram_chat_id        VARCHAR(64),
    marketing_consent       BOOLEAN NOT NULL,
    consent_version         VARCHAR(32),
    consent_at              TIMESTAMP,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    first_visit_at          TIMESTAMP,
    last_visit_at           TIMESTAMP,
    completed_visit_count   INT NOT NULL DEFAULT 0,
    no_show_count           INT NOT NULL DEFAULT 0,
    late_cancel_count       INT NOT NULL DEFAULT 0,
    completed_value_minor   INT NOT NULL DEFAULT 0,
    anonymized_at           TIMESTAMP,
    CONSTRAINT client_pk PRIMARY KEY (id),
    CONSTRAINT client_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT client_phone_uq UNIQUE (tenant_id, normalized_phone)
);

CREATE TABLE appointment (
    id                          UUID NOT NULL,
    tenant_id                   UUID NOT NULL,
    specialist_id               UUID NOT NULL,
    service_id                  UUID NOT NULL,
    client_id                   UUID NOT NULL,
    status                      VARCHAR(32) NOT NULL,
    service_start               TIMESTAMP NOT NULL,
    service_end                 TIMESTAMP NOT NULL,
    occupied_start              TIMESTAMP NOT NULL,
    occupied_end                TIMESTAMP NOT NULL,
    service_name_snapshot       VARCHAR(256) NOT NULL,
    price_snapshot              INT NOT NULL,
    duration_snapshot           INT NOT NULL,
    buffer_before_snapshot      INT NOT NULL,
    buffer_after_snapshot       INT NOT NULL,
    currency_code               CHAR(3) NOT NULL,
    discount_amount             INT NOT NULL DEFAULT 0,
    package_value_used          INT NOT NULL DEFAULT 0,
    amount_received             INT,
    entered_name                VARCHAR(256),
    note                        VARCHAR(4096),
    source                      VARCHAR(32) NOT NULL,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT appointment_pk PRIMARY KEY (id),
    CONSTRAINT appointment_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT appointment_specialist_fk FOREIGN KEY (specialist_id) REFERENCES specialist (id),
    CONSTRAINT appointment_service_fk FOREIGN KEY (service_id) REFERENCES catalog_service (id),
    CONSTRAINT appointment_client_fk FOREIGN KEY (client_id) REFERENCES client (id)
);

CREATE INDEX appointment_tenant_time_idx ON appointment (tenant_id, occupied_start, occupied_end);
CREATE INDEX appointment_specialist_time_idx ON appointment (specialist_id, occupied_start);
CREATE INDEX appointment_client_idx ON appointment (client_id);

CREATE TABLE appointment_event (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    appointment_id      UUID NOT NULL,
    event_type          VARCHAR(64) NOT NULL,
    payload             VARCHAR(8192),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT appointment_event_pk PRIMARY KEY (id, created_at),
    CONSTRAINT appointment_event_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE INDEX appointment_event_appt_idx ON appointment_event (appointment_id, created_at);

CREATE TABLE booking_access_token (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    appointment_id      UUID NOT NULL,
    token_hash          VARCHAR(64) NOT NULL,
    expires_at          TIMESTAMP NOT NULL,
    revoked_at          TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT booking_access_token_pk PRIMARY KEY (id),
    CONSTRAINT booking_access_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT booking_access_token_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT booking_access_token_appt_fk FOREIGN KEY (appointment_id) REFERENCES appointment (id)
);

CREATE TABLE owner_task (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    kind                VARCHAR(64) NOT NULL,
    title               VARCHAR(256) NOT NULL,
    body                VARCHAR(4096) NOT NULL,
    copy_text           VARCHAR(4096),
    link                VARCHAR(1024),
    appointment_id      UUID,
    client_id           UUID,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT owner_task_pk PRIMARY KEY (id),
    CONSTRAINT owner_task_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE INDEX owner_task_open_idx ON owner_task (tenant_id, created_at);

CREATE TABLE service_package (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    client_id           UUID NOT NULL,
    name                VARCHAR(256) NOT NULL,
    total_sessions      INT NOT NULL,
    remaining_sessions  INT NOT NULL,
    value_minor         INT NOT NULL,
    currency_code       CHAR(3) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT service_package_pk PRIMARY KEY (id),
    CONSTRAINT service_package_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT service_package_client_fk FOREIGN KEY (client_id) REFERENCES client (id)
);

CREATE TABLE package_ledger (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    package_id          UUID NOT NULL,
    appointment_id      UUID,
    delta_sessions      INT NOT NULL,
    note                VARCHAR(512),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT package_ledger_pk PRIMARY KEY (id),
    CONSTRAINT package_ledger_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT package_ledger_package_fk FOREIGN KEY (package_id) REFERENCES service_package (id)
);

CREATE TABLE notification_delivery (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    channel_id          VARCHAR(32) NOT NULL,
    destination         VARCHAR(256),
    template_key        VARCHAR(128) NOT NULL,
    payload             VARCHAR(8192),
    status              VARCHAR(32) NOT NULL,
    error               VARCHAR(2048),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT notification_delivery_pk PRIMARY KEY (id, created_at),
    CONSTRAINT notification_delivery_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE metric_snapshot (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    specialist_id       UUID,
    window_start        DATE NOT NULL,
    window_end          DATE NOT NULL,
    metric_key          VARCHAR(64) NOT NULL,
    value_numeric       DOUBLE PRECISION,
    sample_size         INT NOT NULL,
    insufficient_data   BOOLEAN NOT NULL,
    explanation         VARCHAR(1024) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT metric_snapshot_pk PRIMARY KEY (id, created_at),
    CONSTRAINT metric_snapshot_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE trust_state (
    tenant_id           UUID NOT NULL,
    client_id           UUID NOT NULL,
    level               VARCHAR(16) NOT NULL,
    reasons             VARCHAR(2048) NOT NULL,
    override_level      VARCHAR(16),
    override_reason     VARCHAR(1024),
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT trust_state_pk PRIMARY KEY (tenant_id, client_id),
    CONSTRAINT trust_state_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT trust_state_client_fk FOREIGN KEY (client_id) REFERENCES client (id)
);

CREATE TABLE ai_draft (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    conversation_id     UUID NOT NULL,
    tool_name           VARCHAR(64) NOT NULL,
    payload             VARCHAR(8192) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at        TIMESTAMP,
    CONSTRAINT ai_draft_pk PRIMARY KEY (id),
    CONSTRAINT ai_draft_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE ai_prompt_log (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    conversation_id     UUID,
    provider            VARCHAR(64) NOT NULL,
    model               VARCHAR(128) NOT NULL,
    prompt              VARCHAR(8192) NOT NULL,
    response            VARCHAR(8192),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_prompt_log_pk PRIMARY KEY (id, created_at),
    CONSTRAINT ai_prompt_log_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE growth_signal (
    id                  UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    signal_key          VARCHAR(64) NOT NULL,
    title               VARCHAR(256) NOT NULL,
    evidence            VARCHAR(1024) NOT NULL,
    suggested_action    VARCHAR(1024) NOT NULL,
    severity            VARCHAR(16) NOT NULL DEFAULT 'INFO',
    action_type         VARCHAR(64),
    action_payload      VARCHAR(2048),
    dismissed_at        TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT growth_signal_pk PRIMARY KEY (id, created_at),
    CONSTRAINT growth_signal_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);
