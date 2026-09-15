package com.aibusinessmanager.platform.ai;

import com.aibusinessmanager.platform.error.DomainException;
import com.aibusinessmanager.platform.persistence.TenantAwareDsl;
import com.aibusinessmanager.platform.tenancy.TenantContext;
import com.aibusinessmanager.platform.time.Utc;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

import static com.aibusinessmanager.platform.jooq.Tables.AI_DRAFT;
import static com.aibusinessmanager.platform.jooq.Tables.AI_PROMPT_LOG;

@Repository
public class JooqAiStore implements AiStore {

    private final DSLContext dsl;

    public JooqAiStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void logPrompt(UUID conversationId, String provider, String model, String prompt, String response) {
        dsl.insertInto(AI_PROMPT_LOG)
                .set(AI_PROMPT_LOG.ID, UUID.randomUUID())
                .set(AI_PROMPT_LOG.TENANT_ID, TenantContext.require())
                .set(AI_PROMPT_LOG.CONVERSATION_ID, conversationId)
                .set(AI_PROMPT_LOG.PROVIDER, provider)
                .set(AI_PROMPT_LOG.MODEL, model)
                .set(AI_PROMPT_LOG.PROMPT, prompt)
                .set(AI_PROMPT_LOG.RESPONSE, response)
                .set(AI_PROMPT_LOG.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }

    @Override
    public UUID saveDraft(UUID conversationId, String toolName, String payload) {
        UUID id = UUID.randomUUID();
        dsl.insertInto(AI_DRAFT)
                .set(AI_DRAFT.ID, id)
                .set(AI_DRAFT.TENANT_ID, TenantContext.require())
                .set(AI_DRAFT.CONVERSATION_ID, conversationId)
                .set(AI_DRAFT.TOOL_NAME, toolName)
                .set(AI_DRAFT.PAYLOAD, payload)
                .set(AI_DRAFT.STATUS, "PENDING")
                .set(AI_DRAFT.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
        return id;
    }

    @Override
    public DraftRecord requireDraft(UUID draftId) {
        return dsl.selectFrom(AI_DRAFT)
                .where(TenantAwareDsl.tenantEquals(AI_DRAFT.TENANT_ID).and(AI_DRAFT.ID.eq(draftId)))
                .fetchOptional()
                .map(r -> new DraftRecord(
                        r.get(AI_DRAFT.ID),
                        r.get(AI_DRAFT.CONVERSATION_ID),
                        r.get(AI_DRAFT.TOOL_NAME),
                        r.get(AI_DRAFT.PAYLOAD),
                        r.get(AI_DRAFT.STATUS)
                ))
                .orElseThrow(() -> DomainException.notFound("DRAFT_NOT_FOUND", "Draft not found"));
    }

    @Override
    public void markConfirmed(UUID draftId) {
        dsl.update(AI_DRAFT)
                .set(AI_DRAFT.STATUS, "CONFIRMED")
                .set(AI_DRAFT.CONFIRMED_AT, Utc.toLocal(Instant.now()))
                .where(TenantAwareDsl.tenantEquals(AI_DRAFT.TENANT_ID).and(AI_DRAFT.ID.eq(draftId)))
                .execute();
    }
}
