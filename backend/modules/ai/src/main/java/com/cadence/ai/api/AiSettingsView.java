package com.cadence.ai.api;

import java.time.Instant;

/**
 * AI settings of the tenant (ADR 0002 §8). {@code monthlyTokenBudget} is the effective limit, set by the operator and
 * read-only for the owner; {@code period} is the current month {@code YYYY-MM} in the tenant zone and
 * {@code periodResetsAt} the start of the next one.
 */
public record AiSettingsView(
        boolean notesToLlm,
        long monthlyTokenBudget,
        long monthTokensUsed,
        String period,
        Instant periodResetsAt
) {
}
