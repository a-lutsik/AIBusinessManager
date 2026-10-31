package com.cadence.platform.ai;

import com.cadence.platform.error.DomainException;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import com.cadence.platform.jooq.tables.records.AiDraftRecord;
import org.jooq.CommonTableExpression;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.UpdateSetMoreStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.cadence.platform.jooq.Tables.AI_DRAFT;

@Repository
@Transactional
public class JooqAiDraftStore implements AiDraftStore {

    private final DSLContext dsl;
    private final Clock clock;

    public JooqAiDraftStore(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    private LocalDateTime now() {
        return Utc.toLocal(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    @Override
    public UUID save(UUID conversationId, UUID messageId, String toolName, String payloadJson, Instant expiresAt) {
        if (conversationId == null || toolName == null || payloadJson == null || expiresAt == null) {
            throw new IllegalArgumentException("conversationId, toolName, payload and expiresAt are required");
        }
        if (payloadJson.length() > MAX_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("draft payload exceeds " + MAX_PAYLOAD_CHARS + " chars");
        }
        UUID id = UUID.randomUUID();
        dsl.insertInto(AI_DRAFT)
                .set(AI_DRAFT.ID, id)
                .set(AI_DRAFT.TENANT_ID, TenantContext.require())
                .set(AI_DRAFT.CONVERSATION_ID, conversationId)
                .set(AI_DRAFT.MESSAGE_ID, messageId)
                .set(AI_DRAFT.TOOL_NAME, toolName)
                .set(AI_DRAFT.PAYLOAD, payloadJson)
                .set(AI_DRAFT.STATUS, Status.PENDING.name())
                .set(AI_DRAFT.CREATED_AT, now())
                .set(AI_DRAFT.EXPIRES_AT, Utc.toLocal(expiresAt.truncatedTo(ChronoUnit.MICROS)))
                .execute();
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public DraftRecord require(UUID id) {
        return dsl.selectFrom(AI_DRAFT)
                .where(byId(id))
                .fetchOptional()
                .map(JooqAiDraftStore::toRecord)
                .orElseThrow(JooqAiDraftStore::notFound);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DraftRecord requireForUpdate(UUID id) {
        return dsl.selectFrom(AI_DRAFT)
                .where(byId(id))
                .forUpdate()
                .fetchOptional()
                .map(JooqAiDraftStore::toRecord)
                .orElseThrow(JooqAiDraftStore::notFound);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<DraftRecord> lockByClient(UUID clientId) {
        return dsl.selectFrom(AI_DRAFT)
                .where(TenantAwareDsl.tenantEquals(AI_DRAFT.TENANT_ID)
                        .and(AI_DRAFT.PAYLOAD.contains("\"clientId\":\"" + clientId + "\"")))
                .orderBy(AI_DRAFT.ID.asc())
                .forUpdate()
                .fetch(JooqAiDraftStore::toRecord);
    }

    @Override
    public void replacePayload(UUID id, String payloadJson) {
        if (payloadJson.length() > MAX_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("draft payload exceeds " + MAX_PAYLOAD_CHARS + " chars");
        }
        dsl.update(AI_DRAFT).set(AI_DRAFT.PAYLOAD, payloadJson).where(byId(id)).execute();
    }

    @Override
    public boolean markConfirmed(UUID id, UUID appliedEntityId) {
        LocalDateTime now = now();
        return resolvePending(id, Status.CONFIRMED, now, u -> u
                .set(AI_DRAFT.CONFIRMED_AT, now)
                .set(AI_DRAFT.APPLIED_ENTITY_ID, appliedEntityId));
    }

    @Override
    public boolean markRejected(UUID id) {
        return resolvePending(id, Status.REJECTED, now(), UnaryOperator.identity());
    }

    @Override
    public boolean markFailed(UUID id, String code, String message) {
        return resolvePending(id, Status.FAILED, now(), u -> u
                .set(AI_DRAFT.FAILURE_CODE, AiText.truncate(code, MAX_FAILURE_CODE_CHARS))
                .set(AI_DRAFT.FAILURE_MESSAGE, AiText.truncate(message, MAX_FAILURE_MESSAGE_CHARS)));
    }

    @Override
    public boolean markExpired(UUID id) {
        return resolvePending(id, Status.EXPIRED, now(), UnaryOperator.identity());
    }

    /** {@code UPDATE ... WHERE id AND status = 'PENDING'}: true when exactly that row changed. */
    private boolean resolvePending(
            UUID id,
            Status target,
            LocalDateTime now,
            UnaryOperator<UpdateSetMoreStep<AiDraftRecord>> extra
    ) {
        UpdateSetMoreStep<AiDraftRecord> update = dsl.update(AI_DRAFT)
                .set(AI_DRAFT.STATUS, target.name())
                .set(AI_DRAFT.RESOLVED_AT, now);
        return extra.apply(update)
                .where(byId(id).and(AI_DRAFT.STATUS.eq(Status.PENDING.name())))
                .execute() == 1;
    }

    /** Locks in id order, like {@link #lockByClient}, so the two cannot deadlock on a set of drafts. */
    @Override
    public int expireOverdue(Instant now) {
        LocalDateTime at = Utc.toLocal(now.truncatedTo(ChronoUnit.MICROS));
        CommonTableExpression<Record1<UUID>> due = DSL.name("due").as(DSL.select(AI_DRAFT.ID)
                .from(AI_DRAFT)
                .where(TenantAwareDsl.tenantEquals(AI_DRAFT.TENANT_ID)
                        .and(AI_DRAFT.STATUS.eq(Status.PENDING.name()))
                        .and(AI_DRAFT.EXPIRES_AT.lt(at)))
                .orderBy(AI_DRAFT.ID.asc())
                .forUpdate());
        return dsl.with(due)
                .update(AI_DRAFT)
                .set(AI_DRAFT.STATUS, Status.EXPIRED.name())
                .set(AI_DRAFT.RESOLVED_AT, at)
                .where(TenantAwareDsl.tenantEquals(AI_DRAFT.TENANT_ID)
                        .and(AI_DRAFT.ID.in(DSL.select(due.field(AI_DRAFT.ID)).from(due))))
                .execute();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DraftRecord> list(UUID conversationId, Status status) {
        Condition condition = TenantAwareDsl.tenantEquals(AI_DRAFT.TENANT_ID);
        if (conversationId != null) {
            condition = condition.and(AI_DRAFT.CONVERSATION_ID.eq(conversationId));
        }
        if (status != null) {
            condition = condition.and(AI_DRAFT.STATUS.eq(status.name()));
        }
        return dsl.selectFrom(AI_DRAFT)
                .where(condition)
                .orderBy(AI_DRAFT.CREATED_AT.desc(), AI_DRAFT.ID.desc())
                .limit(100)
                .fetch(JooqAiDraftStore::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DraftRecord> listByMessage(UUID messageId) {
        return dsl.selectFrom(AI_DRAFT)
                .where(TenantAwareDsl.tenantEquals(AI_DRAFT.TENANT_ID).and(AI_DRAFT.MESSAGE_ID.eq(messageId)))
                .orderBy(AI_DRAFT.CREATED_AT.asc(), AI_DRAFT.ID.asc())
                .fetch(JooqAiDraftStore::toRecord);
    }

    private static Condition byId(UUID id) {
        return TenantAwareDsl.tenantEquals(AI_DRAFT.TENANT_ID).and(AI_DRAFT.ID.eq(id));
    }

    private static DomainException notFound() {
        return DomainException.notFound("DRAFT_NOT_FOUND", "Draft not found");
    }

    private static Instant instant(LocalDateTime utc) {
        return utc == null ? null : Utc.toInstant(utc);
    }

    private static DraftRecord toRecord(AiDraftRecord r) {
        return new DraftRecord(
                r.getId(),
                r.getConversationId(),
                r.getMessageId(),
                r.getToolName(),
                r.getPayload(),
                Status.valueOf(r.getStatus()),
                instant(r.getCreatedAt()),
                instant(r.getExpiresAt()),
                instant(r.getConfirmedAt()),
                instant(r.getResolvedAt()),
                r.getAppliedEntityId(),
                r.getFailureCode(),
                r.getFailureMessage()
        );
    }
}
