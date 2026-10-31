package com.cadence.ai.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code app.ai.*}; limits of ADR 0002 §10 fall back to their defaults when unset or not positive. */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        String provider,
        String fallbackProvider,
        String model,
        String reasoningEffort,
        DeepSeek deepseek,
        long defaultMonthlyTokenBudget,
        int maxToolIterations,
        int maxToolCallsPerTurn,
        int maxDraftsPerTurn,
        int maxToolResultChars,
        int maxHistoryMessages,
        int maxUserMessageChars,
        int maxCompletionTokens,
        Duration draftTtl
) {
    public AiProperties {
        if (provider == null || provider.isBlank()) {
            provider = "stub";
        }
        if (fallbackProvider != null && fallbackProvider.isBlank()) {
            fallbackProvider = null;
        }
        if (model == null || model.isBlank()) {
            model = "deepseek-flash";
        }
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            reasoningEffort = "high";
        }
        if (deepseek == null) {
            deepseek = new DeepSeek("", "https://api.deepseek.com", "disabled", Duration.ofSeconds(90));
        }
        defaultMonthlyTokenBudget = defaultMonthlyTokenBudget <= 0 ? 1_000_000 : defaultMonthlyTokenBudget;
        maxToolIterations = positive(maxToolIterations, 6);
        maxToolCallsPerTurn = positive(maxToolCallsPerTurn, 10);
        maxDraftsPerTurn = positive(maxDraftsPerTurn, 3);
        maxToolResultChars = positive(maxToolResultChars, 4000);
        maxHistoryMessages = positive(maxHistoryMessages, 20);
        maxUserMessageChars = positive(maxUserMessageChars, 4000);
        maxCompletionTokens = positive(maxCompletionTokens, 1024);
        if (draftTtl == null || draftTtl.isZero() || draftTtl.isNegative()) {
            draftTtl = Duration.ofHours(24);
        }
    }

    /** The given provider settings with every limit at its default. */
    public static AiProperties withDefaultLimits(
            String provider,
            String fallbackProvider,
            String model,
            String reasoningEffort,
            DeepSeek deepseek
    ) {
        return new AiProperties(provider, fallbackProvider, model, reasoningEffort, deepseek,
                0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    private static int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    public record DeepSeek(
            String apiKey,
            String baseUrl,
            String thinking,
            Duration timeout
    ) {
        public DeepSeek {
            if (apiKey == null) {
                apiKey = "";
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.deepseek.com";
            }
            if (thinking == null || thinking.isBlank()) {
                thinking = "disabled";
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                timeout = Duration.ofSeconds(90);
            }
        }
    }
}
