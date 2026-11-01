package com.cadence.platform.ai;

import com.cadence.platform.error.DomainException;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import com.cadence.platform.jooq.tables.records.AiConversationRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.AI_CONVERSATION;
import static com.cadence.platform.jooq.Tables.AI_PROMPT_LOG;
import static com.cadence.platform.jooq.Tables.AI_PSEUDONYM;
import static com.cadence.platform.jooq.Tables.AI_TOOL_CALL;

/** Each method runs in its own transaction or joins the caller's, so the RLS tenant setting is bound. */
@Repository
@Transactional
public class JooqAiConversationStore implements AiConversationStore {

    private final DSLContext dsl;
    private final Clock clock;

    public JooqAiConversationStore(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    @Override
    public UUID create(String locale) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = Utc.toLocal(clock.instant().truncatedTo(ChronoUnit.MICROS));
        dsl.insertInto(AI_CONVERSATION)
                .set(AI_CONVERSATION.ID, id)
                .set(AI_CONVERSATION.TENANT_ID, TenantContext.require())
                .set(AI_CONVERSATION.LOCALE, locale)
                .set(AI_CONVERSATION.CREATED_AT, now)
                .set(AI_CONVERSATION.LAST_MESSAGE_AT, now)
                .execute();
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationRecord require(UUID id) {
        return dsl.selectFrom(AI_CONVERSATION)
                .where(TenantAwareDsl.tenantEquals(AI_CONVERSATION.TENANT_ID).and(AI_CONVERSATION.ID.eq(id)))
                .fetchOptional()
                .map(JooqAiConversationStore::toRecord)
                .orElseThrow(JooqAiConversationStore::notFound);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConversationRecord lockForUpdate(UUID id) {
        return dsl.selectFrom(AI_CONVERSATION)
                .where(TenantAwareDsl.tenantEquals(AI_CONVERSATION.TENANT_ID).and(AI_CONVERSATION.ID.eq(id)))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(JooqAiConversationStore::toRecord)
                .orElseThrow(JooqAiConversationStore::notFound);
    }

    @Override
    public void touch(UUID id, Instant at) {
        LocalDateTime local = Utc.toLocal(at.truncatedTo(ChronoUnit.MICROS));
        dsl.update(AI_CONVERSATION)
                .set(AI_CONVERSATION.LAST_MESSAGE_AT, local)
                .where(TenantAwareDsl.tenantEquals(AI_CONVERSATION.TENANT_ID)
                        .and(AI_CONVERSATION.ID.eq(id))
                        .and(AI_CONVERSATION.LAST_MESSAGE_AT.lt(local)))
                .execute();
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return dsl.deleteFrom(AI_CONVERSATION)
                .where(TenantAwareDsl.tenantEquals(AI_CONVERSATION.TENANT_ID)
                        .and(AI_CONVERSATION.LAST_MESSAGE_AT.lt(Utc.toLocal(cutoff))))
                .execute();
    }

    @Override
    public int deleteNaming(AiPseudonymStore.Kind kind, UUID entityId) {
        List<UUID> ids = dsl.selectDistinct(AI_PSEUDONYM.CONVERSATION_ID)
                .from(AI_PSEUDONYM)
                .where(TenantAwareDsl.tenantEquals(AI_PSEUDONYM.TENANT_ID)
                        .and(AI_PSEUDONYM.KIND.eq(kind.name()))
                        .and(AI_PSEUDONYM.ENTITY_ID.eq(entityId)))
                .fetch(AI_PSEUDONYM.CONVERSATION_ID);
        if (ids.isEmpty()) {
            return 0;
        }
        // The dialogue first: its row lock makes a log insert of a running turn wait and then skip, and a log row committed
        // before it is seen by the deletes below.
        int deleted = dsl.deleteFrom(AI_CONVERSATION)
                .where(TenantAwareDsl.tenantEquals(AI_CONVERSATION.TENANT_ID).and(AI_CONVERSATION.ID.in(ids)))
                .execute();
        dsl.deleteFrom(AI_PROMPT_LOG)
                .where(TenantAwareDsl.tenantEquals(AI_PROMPT_LOG.TENANT_ID).and(AI_PROMPT_LOG.CONVERSATION_ID.in(ids)))
                .execute();
        dsl.deleteFrom(AI_TOOL_CALL)
                .where(TenantAwareDsl.tenantEquals(AI_TOOL_CALL.TENANT_ID).and(AI_TOOL_CALL.CONVERSATION_ID.in(ids)))
                .execute();
        return deleted;
    }

    static DomainException notFound() {
        return DomainException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found");
    }

    private static ConversationRecord toRecord(AiConversationRecord r) {
        return new ConversationRecord(
                r.getId(),
                r.getLocale(),
                Utc.toInstant(r.getCreatedAt()),
                Utc.toInstant(r.getLastMessageAt())
        );
    }
}
