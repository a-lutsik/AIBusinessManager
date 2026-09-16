package com.cadence.ai.internal;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes chat completion to the configured provider and resolves an optional
 * fallback. Fails fast at startup when the selected provider is unknown or
 * misconfigured, so a typo in {@code app.ai.provider} surfaces immediately.
 */
@Component
public class LlmProviderRegistry {

    private final Map<String, LlmProvider> providers;
    private final AiProperties properties;

    public LlmProviderRegistry(List<LlmProvider> providers, AiProperties properties) {
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(LlmProvider::provider, Function.identity()));
        this.properties = properties;
        validateAndPrepare(properties.provider());
        if (hasFallback()) {
            validateAndPrepare(properties.fallbackProvider());
        }
    }

    public LlmProvider primary() {
        return require(properties.provider());
    }

    public LlmProvider fallback() {
        if (!hasFallback()) {
            return null;
        }
        return require(properties.fallbackProvider());
    }

    public List<String> supportedProviders() {
        return List.copyOf(providers.keySet());
    }

    private boolean hasFallback() {
        return properties.fallbackProvider() != null && !properties.fallbackProvider().isBlank();
    }

    private void validateAndPrepare(String id) {
        LlmProvider provider = providers.get(id);
        if (provider == null) {
            throw new IllegalStateException(
                    "Unknown app.ai.provider '" + id + "'; supported providers: " + providers.keySet()
            );
        }
        provider.validate();
    }

    private LlmProvider require(String id) {
        LlmProvider provider = providers.get(id);
        if (provider == null) {
            throw new IllegalStateException(
                    "Unknown app.ai.provider '" + id + "'; supported providers: " + providers.keySet()
            );
        }
        return provider;
    }
}
