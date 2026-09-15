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
