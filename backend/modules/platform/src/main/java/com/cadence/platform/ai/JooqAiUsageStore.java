package com.cadence.platform.ai;

import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import static com.cadence.platform.jooq.Tables.AI_USAGE_MONTH;

@Repository
@Transactional
public class JooqAiUsageStore implements AiUsageStore {

    private final DSLContext dsl;
    private final Clock clock;

    public JooqAiUsageStore(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    private LocalDateTime now() {
        return Utc.toLocal(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /** 'YYYY-MM' (CHAR(7)). */
    static String key(YearMonth period) {
        String key = period.toString();
        if (key.length() != 7) {
            throw new IllegalArgumentException("period out of range: " + period);
        }
        return key;
    }

    @Override
    @Transactional(readOnly = true)
    public Usage usage(YearMonth period) {
        return dsl.selectFrom(AI_USAGE_MONTH)
                .where(TenantAwareDsl.tenantEquals(AI_USAGE_MONTH.TENANT_ID).and(AI_USAGE_MONTH.PERIOD.eq(key(period))))
                .fetchOptional()
                .map(r -> new Usage(
                        r.getPromptTokens(),
                        r.getCompletionTokens(),
                        r.getLlmCalls(),
                        r.getToolCalls(),
                        r.getExceededAt() == null ? null : Utc.toInstant(r.getExceededAt())))
                .orElse(Usage.NONE);
    }

    @Override
    public void add(YearMonth period, long promptTokens, long completionTokens, int llmCalls, int toolCalls) {
        if (promptTokens < 0 || completionTokens < 0 || llmCalls < 0 || toolCalls < 0) {
            throw new IllegalArgumentException("usage increments must not be negative");
        }
        LocalDateTime now = now();
        // One statement: INSERT ... ON CONFLICT (tenant_id, period) DO UPDATE SET x = ai_usage_month.x + EXCLUDED.x.
        dsl.insertInto(AI_USAGE_MONTH)
                .set(AI_USAGE_MONTH.TENANT_ID, TenantContext.require())
                .set(AI_USAGE_MONTH.PERIOD, key(period))
                .set(AI_USAGE_MONTH.PROMPT_TOKENS, promptTokens)
                .set(AI_USAGE_MONTH.COMPLETION_TOKENS, completionTokens)
                .set(AI_USAGE_MONTH.LLM_CALLS, llmCalls)
                .set(AI_USAGE_MONTH.TOOL_CALLS, toolCalls)
                .set(AI_USAGE_MONTH.UPDATED_AT, now)
                .onConflict(AI_USAGE_MONTH.TENANT_ID, AI_USAGE_MONTH.PERIOD)
                .doUpdate()
                .set(AI_USAGE_MONTH.PROMPT_TOKENS, AI_USAGE_MONTH.PROMPT_TOKENS.plus(DSL.excluded(AI_USAGE_MONTH.PROMPT_TOKENS)))
                .set(AI_USAGE_MONTH.COMPLETION_TOKENS, AI_USAGE_MONTH.COMPLETION_TOKENS.plus(DSL.excluded(AI_USAGE_MONTH.COMPLETION_TOKENS)))
                .set(AI_USAGE_MONTH.LLM_CALLS, AI_USAGE_MONTH.LLM_CALLS.plus(DSL.excluded(AI_USAGE_MONTH.LLM_CALLS)))
                .set(AI_USAGE_MONTH.TOOL_CALLS, AI_USAGE_MONTH.TOOL_CALLS.plus(DSL.excluded(AI_USAGE_MONTH.TOOL_CALLS)))
                .set(AI_USAGE_MONTH.UPDATED_AT, DSL.excluded(AI_USAGE_MONTH.UPDATED_AT))
                .execute();
    }

    @Override
    public boolean markExceeded(YearMonth period) {
        LocalDateTime now = now();
        // Inserts the period row if absent; on conflict updates only while exceeded_at is still NULL (0 rows otherwise).
        return dsl.insertInto(AI_USAGE_MONTH)
                .set(AI_USAGE_MONTH.TENANT_ID, TenantContext.require())
                .set(AI_USAGE_MONTH.PERIOD, key(period))
                .set(AI_USAGE_MONTH.EXCEEDED_AT, now)
                .set(AI_USAGE_MONTH.UPDATED_AT, now)
                .onConflict(AI_USAGE_MONTH.TENANT_ID, AI_USAGE_MONTH.PERIOD)
                .doUpdate()
                .set(AI_USAGE_MONTH.EXCEEDED_AT, DSL.excluded(AI_USAGE_MONTH.EXCEEDED_AT))
                .set(AI_USAGE_MONTH.UPDATED_AT, DSL.excluded(AI_USAGE_MONTH.UPDATED_AT))
                .where(AI_USAGE_MONTH.EXCEEDED_AT.isNull())
                .execute() == 1;
    }
}
