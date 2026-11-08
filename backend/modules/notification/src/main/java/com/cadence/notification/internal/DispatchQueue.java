package com.cadence.notification.internal;

import com.cadence.notification.api.MessageKind;
import com.cadence.notification.api.VisitMessage;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.VISIT_MESSAGE_DISPATCH;

/**
 * The delivery queue and the idempotency key in one table: a row per (visit, kind, visit start), PENDING when queued,
 * SENDING while the worker holds it, DONE when handled. Short transactions only; the provider call happens between them.
 */
@Repository
class DispatchQueue {

    /** A queued message handed to the worker; {@code stale}: a SENDING row left by a process that died, never sent again. */
    record Claimed(UUID tenantId, UUID appointmentId, MessageKind kind, Instant serviceStart, UUID clientId,
                   String serviceName, boolean manualFallback, boolean stale, int attempts) {
    }

    private final DSLContext dsl;

    DispatchQueue(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** False when the key is already taken (queued, in flight or done). */
    @Transactional
    boolean enqueue(VisitMessage message) {
        return dsl.insertInto(VISIT_MESSAGE_DISPATCH)
                .set(VISIT_MESSAGE_DISPATCH.TENANT_ID, TenantContext.require())
                .set(VISIT_MESSAGE_DISPATCH.APPOINTMENT_ID, message.appointmentId())
                .set(VISIT_MESSAGE_DISPATCH.KIND, message.kind().name())
                .set(VISIT_MESSAGE_DISPATCH.SERVICE_START, Utc.toLocal(message.serviceStart()))
                .set(VISIT_MESSAGE_DISPATCH.CLIENT_ID, message.clientId())
                .set(VISIT_MESSAGE_DISPATCH.SERVICE_NAME, message.serviceName())
                .set(VISIT_MESSAGE_DISPATCH.MANUAL_FALLBACK, message.manualFallback())
                .set(VISIT_MESSAGE_DISPATCH.STATUS, "PENDING")
                .set(VISIT_MESSAGE_DISPATCH.CREATED_AT, Utc.toLocal(Instant.now()))
                .onConflictDoNothing()
                .execute() == 1;
    }

    /** Cross-tenant (SECURITY DEFINER function of V17); marks the rows SENDING in this transaction. */
    @Transactional
    List<Claimed> claim(int limit, Instant staleBefore) {
        return dsl.resultQuery("select * from visit_message_claim({0}, {1})", DSL.val(limit), DSL.val(Utc.toLocal(staleBefore)))
                .fetch(r -> new Claimed(
                        r.get(0, UUID.class), r.get(1, UUID.class), MessageKind.valueOf(r.get(2, String.class)),
                        Utc.toInstant(r.get(3, LocalDateTime.class)), r.get(4, UUID.class), r.get(5, String.class),
                        r.get(6, Boolean.class), r.get(7, Boolean.class), r.get(8, Integer.class)));
    }

    /** Tenant scope required. */
    @Transactional
    void done(Claimed c) {
        set(c, "DONE");
    }

    /** Puts a claimed message back in the queue (shutdown, or the preparation failed before anything was sent). */
    @Transactional
    void release(Claimed c) {
        set(c, "PENDING");
    }

    /** Preparation failed: back in the queue after {@code delay}, one attempt counted; the claim skips it until then. */
    @Transactional
    void retryLater(Claimed c, Duration delay) {
        dsl.update(VISIT_MESSAGE_DISPATCH)
                .set(VISIT_MESSAGE_DISPATCH.STATUS, "PENDING")
                .set(VISIT_MESSAGE_DISPATCH.ATTEMPTS, c.attempts() + 1)
                .set(VISIT_MESSAGE_DISPATCH.NEXT_ATTEMPT_AT, Utc.toLocal(Instant.now().plus(delay)))
                .where(key(c))
                .execute();
    }

    private void set(Claimed c, String status) {
        dsl.update(VISIT_MESSAGE_DISPATCH)
                .set(VISIT_MESSAGE_DISPATCH.STATUS, status)
                .where(key(c))
                .execute();
    }

    private static Condition key(Claimed c) {
        return TenantAwareDsl.tenantEquals(VISIT_MESSAGE_DISPATCH.TENANT_ID)
                .and(VISIT_MESSAGE_DISPATCH.APPOINTMENT_ID.eq(c.appointmentId()))
                .and(VISIT_MESSAGE_DISPATCH.KIND.eq(c.kind().name()))
                .and(VISIT_MESSAGE_DISPATCH.SERVICE_START.eq(Utc.toLocal(c.serviceStart())));
    }
}
