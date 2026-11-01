package com.cadence.platform.ai;

import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.AI_CONVERSATION;
import static com.cadence.platform.jooq.Tables.AI_PROMPT_LOG;
import static com.cadence.platform.jooq.Tables.AI_TOOL_CALL;

@Repository
@Transactional
public class JooqAiLogStore implements AiLogStore {

    private static final Logger log = LoggerFactory.getLogger(JooqAiLogStore.class);

    private final DSLContext dsl;
    private final Clock clock;

    public JooqAiLogStore(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    @Override
    public UUID logLlmCall(LlmCall call) {
        UUID id = UUID.randomUUID();
        if (!conversationStands(call.conversationId())) {
            return id;
        }
        dsl.insertInto(AI_PROMPT_LOG)
                .set(AI_PROMPT_LOG.ID, id)
                .set(AI_PROMPT_LOG.TENANT_ID, TenantContext.require())
                .set(AI_PROMPT_LOG.CONVERSATION_ID, call.conversationId())
                .set(AI_PROMPT_LOG.MESSAGE_ID, call.messageId())
                .set(AI_PROMPT_LOG.PROVIDER, AiText.truncate(call.provider(), MAX_PROVIDER_CHARS))
                .set(AI_PROMPT_LOG.MODEL, AiText.truncate(call.model(), MAX_MODEL_CHARS))
                .set(AI_PROMPT_LOG.PROMPT, AiText.truncate(call.prompt(), MAX_PROMPT_CHARS))
                .set(AI_PROMPT_LOG.RESPONSE, AiText.truncate(call.response(), MAX_RESPONSE_CHARS))
                .set(AI_PROMPT_LOG.PROMPT_TOKENS, call.promptTokens())
                .set(AI_PROMPT_LOG.COMPLETION_TOKENS, call.completionTokens())
                .set(AI_PROMPT_LOG.FINISH_REASON, AiText.truncate(call.finishReason(), MAX_FINISH_REASON_CHARS))
                .set(AI_PROMPT_LOG.LATENCY_MS, call.latencyMs())
                .set(AI_PROMPT_LOG.TOOL_CALLS_JSON, AiText.truncate(call.toolCallsJson(), MAX_TOOL_CALLS_JSON_CHARS))
                .set(AI_PROMPT_LOG.CREATED_AT, Utc.toLocal(clock.instant().truncatedTo(ChronoUnit.MICROS)))
                .execute();
        return id;
    }

    @Override
    public UUID logToolCall(ToolCall call) {
        UUID id = UUID.randomUUID();
        if (!conversationStands(call.conversationId())) {
            return id;
        }
        dsl.insertInto(AI_TOOL_CALL)
                .set(AI_TOOL_CALL.ID, id)
                .set(AI_TOOL_CALL.TENANT_ID, TenantContext.require())
                .set(AI_TOOL_CALL.CONVERSATION_ID, call.conversationId())
                .set(AI_TOOL_CALL.LLM_CALL_ID, call.llmCallId())
                .set(AI_TOOL_CALL.TOOL_NAME, AiText.truncate(call.toolName(), MAX_TOOL_NAME_CHARS))
                .set(AI_TOOL_CALL.ARGUMENTS, AiText.truncate(call.argumentsJson(), MAX_TOOL_ARGUMENTS_CHARS))
                .set(AI_TOOL_CALL.RESULT, AiText.truncate(call.resultJson(), MAX_TOOL_RESULT_CHARS))
                .set(AI_TOOL_CALL.STATUS, call.status().name())
                .set(AI_TOOL_CALL.ERROR_CODE, AiText.truncate(call.errorCode(), MAX_CODE_CHARS))
                .set(AI_TOOL_CALL.DURATION_MS, call.durationMs())
                .set(AI_TOOL_CALL.CREATED_AT, Utc.toLocal(clock.instant().truncatedTo(ChronoUnit.MICROS)))
                .execute();
        return id;
    }

    /** Waits for an erasure of the conversation and then reads what it left; held until this log row commits. */
    private boolean conversationStands(UUID conversationId) {
        boolean stands = dsl.selectOne().from(AI_CONVERSATION)
                .where(TenantAwareDsl.tenantEquals(AI_CONVERSATION.TENANT_ID).and(AI_CONVERSATION.ID.eq(conversationId)))
                .forKeyShare()
                .fetchOptional()
                .isPresent();
        if (!stands) {
            log.debug("AI log row skipped, the conversation is gone: conversation={}", conversationId);
        }
        return stands;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LlmCallRecord> listLlmCalls(UUID conversationId) {
        return dsl.selectFrom(AI_PROMPT_LOG)
                .where(TenantAwareDsl.tenantEquals(AI_PROMPT_LOG.TENANT_ID).and(AI_PROMPT_LOG.CONVERSATION_ID.eq(conversationId)))
                .orderBy(AI_PROMPT_LOG.CREATED_AT.asc(), AI_PROMPT_LOG.ID.asc())
                .limit(500)
                .fetch(r -> new LlmCallRecord(r.getId(), Utc.toInstant(r.getCreatedAt()), new LlmCall(
                        r.getConversationId(),
                        r.getMessageId(),
                        r.getProvider(),
                        r.getModel(),
                        r.getPrompt(),
                        r.getResponse(),
                        r.getPromptTokens(),
                        r.getCompletionTokens(),
                        r.getFinishReason(),
                        r.getLatencyMs(),
                        r.getToolCallsJson())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ToolCallRecord> listToolCalls(UUID conversationId) {
        return dsl.selectFrom(AI_TOOL_CALL)
                .where(TenantAwareDsl.tenantEquals(AI_TOOL_CALL.TENANT_ID).and(AI_TOOL_CALL.CONVERSATION_ID.eq(conversationId)))
                .orderBy(AI_TOOL_CALL.CREATED_AT.asc(), AI_TOOL_CALL.ID.asc())
                .limit(500)
                .fetch(r -> new ToolCallRecord(r.getId(), Utc.toInstant(r.getCreatedAt()), new ToolCall(
                        r.getConversationId(),
                        r.getLlmCallId(),
                        r.getToolName(),
                        r.getArguments(),
                        r.getResult(),
                        ToolStatus.valueOf(r.getStatus()),
                        r.getErrorCode(),
                        r.getDurationMs())));
    }
}
