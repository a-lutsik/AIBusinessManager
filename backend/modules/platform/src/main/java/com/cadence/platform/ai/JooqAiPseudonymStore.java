package com.cadence.platform.ai;

import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import com.cadence.platform.jooq.tables.records.AiPseudonymRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.AI_CONVERSATION;
import static com.cadence.platform.jooq.Tables.AI_PSEUDONYM;

@Repository
@Transactional
public class JooqAiPseudonymStore implements AiPseudonymStore {

    private final DSLContext dsl;
    private final Clock clock;

    public JooqAiPseudonymStore(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    @Override
    public String refFor(UUID conversationId, Kind kind, UUID entityId, String display) {
        if (kind == null || entityId == null || display == null) {
            throw new IllegalArgumentException("kind, entityId and display are required");
        }
        // Serialises numbering per conversation: concurrent first appearances queue here until this transaction ends.
        // FOR NO KEY UPDATE, not FOR UPDATE: the FK checks of inserts into ai_message/ai_draft/ai_pseudonym take
        // FOR KEY SHARE on this row, which FOR UPDATE would conflict with (two turns that each saved a draft and then
        // ask for its DRAFT ref would deadlock). NO KEY UPDATE still conflicts with itself.
        boolean locked = dsl.selectOne().from(AI_CONVERSATION)
                .where(TenantAwareDsl.tenantEquals(AI_CONVERSATION.TENANT_ID).and(AI_CONVERSATION.ID.eq(conversationId)))
                .forNoKeyUpdate()
                .fetchOptional()
                .isPresent();
        if (!locked) {
            throw JooqAiConversationStore.notFound();
        }
        Optional<String> existing = tokenOf(conversationId, kind, entityId);
        if (existing.isPresent()) {
            return existing.get();
        }
        int next = dsl.select(DSL.coalesce(DSL.max(AI_PSEUDONYM.TOKEN_NO), 0))
                .from(AI_PSEUDONYM)
                .where(inConversation(conversationId).and(AI_PSEUDONYM.KIND.eq(kind.name())))
                .fetchOne(0, Integer.class) + 1;
        dsl.insertInto(AI_PSEUDONYM)
                .set(AI_PSEUDONYM.TENANT_ID, TenantContext.require())
                .set(AI_PSEUDONYM.CONVERSATION_ID, conversationId)
                .set(AI_PSEUDONYM.KIND, kind.name())
                .set(AI_PSEUDONYM.TOKEN_NO, next)
                .set(AI_PSEUDONYM.TOKEN, kind.name() + "_" + next)
                .set(AI_PSEUDONYM.ENTITY_ID, entityId)
                .set(AI_PSEUDONYM.DISPLAY, AiText.truncate(display, MAX_DISPLAY_CHARS))
                .set(AI_PSEUDONYM.CREATED_AT, Utc.toLocal(clock.instant().truncatedTo(ChronoUnit.MICROS)))
                .onConflictDoNothing()
                .execute();
        return tokenOf(conversationId, kind, entityId)
                .orElseThrow(() -> new IllegalStateException("pseudonym " + kind + "_" + next + " was not stored"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Ref> resolve(UUID conversationId, String token) {
        return byToken(conversationId, token).map(r -> new Ref(Kind.valueOf(r.getKind()), r.getEntityId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> displayOf(UUID conversationId, String token) {
        return byToken(conversationId, token).map(AiPseudonymRecord::getDisplay);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Mapping> all(UUID conversationId) {
        return dsl.selectFrom(AI_PSEUDONYM)
                .where(inConversation(conversationId))
                .orderBy(AI_PSEUDONYM.KIND.asc(), AI_PSEUDONYM.TOKEN_NO.asc())
                .fetch(r -> new Mapping(Kind.valueOf(r.getKind()), r.getTokenNo(), r.getToken(), r.getEntityId(), r.getDisplay()));
    }

    private Optional<String> tokenOf(UUID conversationId, Kind kind, UUID entityId) {
        return dsl.select(AI_PSEUDONYM.TOKEN)
                .from(AI_PSEUDONYM)
                .where(inConversation(conversationId)
                        .and(AI_PSEUDONYM.KIND.eq(kind.name()))
                        .and(AI_PSEUDONYM.ENTITY_ID.eq(entityId)))
                .fetchOptional(AI_PSEUDONYM.TOKEN);
    }

    private Optional<AiPseudonymRecord> byToken(UUID conversationId, String token) {
        if (token == null) {
            return Optional.empty();
        }
        return dsl.selectFrom(AI_PSEUDONYM)
                .where(inConversation(conversationId).and(AI_PSEUDONYM.TOKEN.eq(token)))
                .fetchOptional();
    }

    @Override
    public int replaceDisplay(Kind kind, UUID entityId, String display) {
        return dsl.update(AI_PSEUDONYM)
                .set(AI_PSEUDONYM.DISPLAY, AiText.truncate(display, MAX_DISPLAY_CHARS))
                .where(TenantAwareDsl.tenantEquals(AI_PSEUDONYM.TENANT_ID)
                        .and(AI_PSEUDONYM.KIND.eq(kind.name()))
                        .and(AI_PSEUDONYM.ENTITY_ID.eq(entityId)))
                .execute();
    }

    private static Condition inConversation(UUID conversationId) {
        return TenantAwareDsl.tenantEquals(AI_PSEUDONYM.TENANT_ID).and(AI_PSEUDONYM.CONVERSATION_ID.eq(conversationId));
    }
}
