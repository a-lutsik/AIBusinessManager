package com.aibusinessmanager.ai.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        String provider,
        String fallbackProvider,
        String model,
        String reasoningEffort,
        DeepSeek deepseek
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
