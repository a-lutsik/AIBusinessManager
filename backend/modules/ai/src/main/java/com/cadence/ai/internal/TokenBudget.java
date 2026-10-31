package com.cadence.ai.internal;

import com.cadence.ai.internal.llm.Usage;
import com.cadence.platform.ai.AiSettingsStore;
import com.cadence.platform.ai.AiUsageStore;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenant.TenantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;

/**
 * The tenant's monthly token budget (ADR 0002 §5): check before every LLM call, add after it with an atomic upsert, so
 * parallel chats overshoot by at most one call each. Used only by {@code chat}; the rest of the product never depends on it.
 */
@Component
public class TokenBudget {

    private static final Logger log = LoggerFactory.getLogger(TokenBudget.class);

    public record Month(YearMonth period, long used, long limit, Instant resetsAt) {
    }

    private final AiUsageStore usage;
    private final AiProperties properties;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TokenBudget(
            AiUsageStore usage,
            AiProperties properties,
            AuditLogger audit,
            TransactionTemplate tx,
            Clock clock
    ) {
        this.usage = usage;
        this.properties = properties;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /** The tenant's own limit, else the configured default. */
    public long limit(AiSettingsStore.Settings settings) {
        return settings.monthlyTokenBudget() != null
                ? settings.monthlyTokenBudget()
                : properties.defaultMonthlyTokenBudget();
    }

    /** 429 {@code AI_BUDGET_EXCEEDED} once the month's tokens reached {@code limit}; the first time also marks and audits it. */
    public void assertAvailable(TenantInfo tenant, long limit) {
        YearMonth period = period(tenant);
        long used = usage.usage(period).totalTokens();
        if (used < limit) {
            return;
        }
        tx.executeWithoutResult(status -> {
            if (usage.markExceeded(period)) {
                audit.record("system", "SYSTEM", "ai.budget.exceeded", "ai_usage_month", tenant.id(), null,
                        "{\"period\":\"" + period + "\",\"used\":" + used + ",\"limit\":" + limit + "}",
                        "ai-chat", null);
            }
        });
        log.warn("AI token budget exceeded: tenant={}, period={}, used={}, limit={}", tenant.id(), period, used, limit);
        throw new DomainException("AI_BUDGET_EXCEEDED", 429, "Monthly AI token budget used up: " + used + "/" + limit
                + ", resets " + period.plusMonths(1).atDay(1) + " " + tenant.timezone());
    }

    public void record(TenantInfo tenant, Usage callUsage) {
        usage.add(period(tenant), callUsage.promptTokens(), callUsage.completionTokens(), 1, 0);
    }

    public void recordToolCalls(TenantInfo tenant, int toolCalls) {
        usage.add(period(tenant), 0, 0, 0, toolCalls);
    }

    public Month month(TenantInfo tenant, long limit) {
        YearMonth period = period(tenant);
        return new Month(period, usage.usage(period).totalTokens(), limit,
                period.plusMonths(1).atDay(1).atStartOfDay(tenant.zone()).toInstant());
    }

    private YearMonth period(TenantInfo tenant) {
        return YearMonth.from(clock.instant().atZone(tenant.zone()));
    }
}
