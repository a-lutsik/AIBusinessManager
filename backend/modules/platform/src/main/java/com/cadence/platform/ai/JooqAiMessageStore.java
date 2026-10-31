package com.cadence.platform.ai;

import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import com.cadence.platform.jooq.tables.records.AiMessageRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.AI_CONVERSATION;
import static com.cadence.platform.jooq.Tables.AI_MESSAGE;

@Repository
@Transactional
public class JooqAiMessageStore implements AiMessageStore {

    private final DSLContext dsl;
    private final Clock clock;

    public JooqAiMessageStore(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    @Override
    public UUID append(UUID conversationId, Role role, String maskedContent, String provider, String model) {
        if (role == null || maskedContent == null) {
            throw new IllegalArgumentException("role and content are required");
        }
        // Serialises appends per conversation (and with refFor/lockForUpdate), so the max(created_at) read below and the
        // insert are not interleaved with another append; NO KEY UPDATE keeps FK checks of child inserts unblocked.
        boolean visible = dsl.selectOne().from(AI_CONVERSATION)
                .where(TenantAwareDsl.tenantEquals(AI_CONVERSATION.TENANT_ID).and(AI_CONVERSATION.ID.eq(conversationId)))
                .forNoKeyUpdate()
                .fetchOptional()
                .isPresent();
        if (!visible) {
            throw JooqAiConversationStore.notFound();
        }
        LocalDateTime at = Utc.toLocal(clock.instant().truncatedTo(ChronoUnit.MICROS));
        LocalDateTime previous = dsl.select(DSL.max(AI_MESSAGE.CREATED_AT))
                .from(AI_MESSAGE)
                .where(TenantAwareDsl.tenantEquals(AI_MESSAGE.TENANT_ID).and(AI_MESSAGE.CONVERSATION_ID.eq(conversationId)))
                .fetchOne(0, LocalDateTime.class);
        if (previous != null && !at.isAfter(previous)) {
            at = previous.plus(1, ChronoUnit.MICROS);
        }
        UUID id = UUID.randomUUID();
        dsl.insertInto(AI_MESSAGE)
                .set(AI_MESSAGE.ID, id)
                .set(AI_MESSAGE.TENANT_ID, TenantContext.require())
                .set(AI_MESSAGE.CONVERSATION_ID, conversationId)
                .set(AI_MESSAGE.ROLE, role.name())
                .set(AI_MESSAGE.CONTENT, AiText.truncate(maskedContent, MAX_CONTENT_CHARS))
                .set(AI_MESSAGE.PROVIDER, AiText.truncate(provider, AiLogStore.MAX_PROVIDER_CHARS))
                .set(AI_MESSAGE.MODEL, AiText.truncate(model, AiLogStore.MAX_MODEL_CHARS))
                .set(AI_MESSAGE.CREATED_AT, at)
                .execute();
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageRecord> lastN(UUID conversationId, int n) {
        if (n <= 0) {
            return List.of();
        }
        List<MessageRecord> newestFirst = new ArrayList<>(dsl.selectFrom(AI_MESSAGE)
                .where(TenantAwareDsl.tenantEquals(AI_MESSAGE.TENANT_ID).and(AI_MESSAGE.CONVERSATION_ID.eq(conversationId)))
                .orderBy(AI_MESSAGE.CREATED_AT.desc(), AI_MESSAGE.ID.desc())
                .limit(n)
                .fetch(JooqAiMessageStore::toRecord));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageRecord> list(UUID conversationId) {
        return dsl.selectFrom(AI_MESSAGE)
                .where(TenantAwareDsl.tenantEquals(AI_MESSAGE.TENANT_ID).and(AI_MESSAGE.CONVERSATION_ID.eq(conversationId)))
                .orderBy(AI_MESSAGE.CREATED_AT.asc(), AI_MESSAGE.ID.asc())
                .fetch(JooqAiMessageStore::toRecord);
    }

    private static MessageRecord toRecord(AiMessageRecord r) {
        return new MessageRecord(
                r.getId(),
                r.getConversationId(),
                Role.valueOf(r.getRole()),
                r.getContent(),
                r.getProvider(),
                r.getModel(),
                Utc.toInstant(r.getCreatedAt())
        );
    }
}
