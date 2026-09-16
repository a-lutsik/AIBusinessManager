package com.cadence.platform.outbox;

import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.OUTBOX_EVENT;

@Service
@Primary
public class JooqOutboxPublisher implements OutboxPublisher {

    private final DSLContext dsl;

    public JooqOutboxPublisher(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void publish(String type, String aggregateType, UUID aggregateId, String jsonPayload) {
        dsl.insertInto(OUTBOX_EVENT)
                .set(OUTBOX_EVENT.ID, UUID.randomUUID())
                .set(OUTBOX_EVENT.TENANT_ID, TenantContext.require())
                .set(OUTBOX_EVENT.TYPE, type)
                .set(OUTBOX_EVENT.AGGREGATE_TYPE, aggregateType)
                .set(OUTBOX_EVENT.AGGREGATE_ID, aggregateId)
                .set(OUTBOX_EVENT.PAYLOAD, jsonPayload)
                .set(OUTBOX_EVENT.CREATED_AT, Utc.toLocal(Instant.now()))
                .set(OUTBOX_EVENT.ATTEMPTS, 0)
                .execute();
    }
}
