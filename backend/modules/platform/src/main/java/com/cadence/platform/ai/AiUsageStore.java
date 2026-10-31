package com.cadence.platform.ai;

import java.time.Instant;
import java.time.YearMonth;

/**
 * {@code ai_usage_month}: tokens and calls per tenant and month (ADR 0002 §5). The period is the month in the
 * tenant's zone; the caller computes it.
 */
public interface AiUsageStore {

    record Usage(long promptTokens, long completionTokens, int llmCalls, int toolCalls, Instant exceededAt) {

        public static final Usage NONE = new Usage(0, 0, 0, 0, null);

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    /** {@link Usage#NONE} when nothing was recorded for the period. */
    Usage usage(YearMonth period);

    /** Adds to the period's counters in one atomic upsert; concurrent adds never lose an increment. */
    void add(YearMonth period, long promptTokens, long completionTokens, int llmCalls, int toolCalls);

    /** Sets {@code exceeded_at} for the period; true only for the call that set it first. */
    boolean markExceeded(YearMonth period);
}
