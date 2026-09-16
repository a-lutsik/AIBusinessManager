package com.cadence.platform.audit;

import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.AUDIT_LOG;

@Service
@Primary
public class JooqAuditLogger implements AuditLogger {

    private final DSLContext dsl;

    public JooqAuditLogger(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void record(
            String actorId,
            String actorType,
            String action,
            String entityType,
            UUID entityId,
            String beforeJson,
            String afterJson,
            String source,
            String requestId
    ) {
        UUID tenantId = TenantContext.current().orElse(null);
        dsl.insertInto(AUDIT_LOG)
                .set(AUDIT_LOG.ID, UUID.randomUUID())
                .set(AUDIT_LOG.TENANT_ID, tenantId == null ? UUID.fromString("00000000-0000-4000-8000-000000000000") : tenantId)
                .set(AUDIT_LOG.ACTOR_ID, actorId)
                .set(AUDIT_LOG.ACTOR_TYPE, actorType)
                .set(AUDIT_LOG.ACTION, action)
                .set(AUDIT_LOG.ENTITY_TYPE, entityType)
                .set(AUDIT_LOG.ENTITY_ID, entityId)
                .set(AUDIT_LOG.BEFORE_STATE, beforeJson)
                .set(AUDIT_LOG.AFTER_STATE, afterJson)
                .set(AUDIT_LOG.SOURCE, source)
                .set(AUDIT_LOG.REQUEST_ID, requestId)
                .set(AUDIT_LOG.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }
}
