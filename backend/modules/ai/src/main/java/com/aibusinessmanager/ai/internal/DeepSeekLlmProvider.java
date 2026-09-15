package com.aibusinessmanager.ai.internal;

import com.aibusinessmanager.platform.error.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final String model;
    private final String thinkingType;
    private final String reasoningEffort;
    private final String apiKey;

    @Autowired
    public DeepSeekLlmProvider(AiProperties properties) {
        this(properties, createClient(properties));
    }

    DeepSeekLlmProvider(AiProperties properties, RestClient client) {
        AiProperties.DeepSeek ds = properties.deepseek();
        this.apiKey = ds == null ? "" : ds.apiKey();
        this.model = properties.model();
        this.thinkingType = ds == null ? "disabled" : ds.thinking();
        this.reasoningEffort = properties.reasoningEffort();
        this.client = client;
    }

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public void validate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "DEEPSEEK_API_KEY / app.ai.deepseek.api-key is required when app.ai.provider=deepseek"
            );
        }
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", false);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)
        ));
        payload.put("thinking", thinkingPayload());

        String json;
        try {
            json = client.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int code = res.getStatusCode().value();
                        if (code == 401 || code == 403) {
                            throw DomainException.badGateway(
                                    "LLM_UNAUTHORIZED",
                                    "DeepSeek authentication failed; check DEEPSEEK_API_KEY"
                            );
                        }
                        throw DomainException.badGateway("LLM_UNAVAILABLE", "DeepSeek request failed (" + code + ")");
                    })
                    .body(String.class);
        } catch (DomainException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("DeepSeek chat completion failed: {}", e.getMessage());
            throw DomainException.badGateway("LLM_UNAVAILABLE", "DeepSeek request failed");
        }

        String content = extractContent(json);
        if (content.isBlank()) {
            throw DomainException.badGateway("LLM_UNAVAILABLE", "DeepSeek returned an empty completion");
        }
        return content;
    }

    private Map<String, Object> thinkingPayload() {
        Map<String, Object> thinking = new LinkedHashMap<>();
        thinking.put("type", thinkingType);
        if (isThinkingEnabled() && reasoningEffort != null && !reasoningEffort.isBlank()) {
            thinking.put("reasoning_effort", reasoningEffort);
        }
        return thinking;
    }

    private boolean isThinkingEnabled() {
        return thinkingType != null && !thinkingType.isBlank() && !"disabled".equalsIgnoreCase(thinkingType);
    }

    private static String extractContent(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode content = MAPPER.readTree(json).path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                return "";
            }
            if (content.isTextual()) {
                return content.asText();
            }
            return content.toString();
        } catch (Exception e) {
            throw DomainException.badGateway("LLM_UNAVAILABLE", "DeepSeek returned an unreadable completion");
        }
    }

    private static RestClient createClient(AiProperties properties) {
        AiProperties.DeepSeek ds = properties.deepseek();
        String baseUrl = ds == null || ds.baseUrl() == null || ds.baseUrl().isBlank()
                ? "https://api.deepseek.com"
                : trimSlash(ds.baseUrl());
        Duration timeout = ds == null ? Duration.ofSeconds(90) : ds.timeout();
        String key = ds == null ? "" : ds.apiKey();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        );
        factory.setReadTimeout(timeout);
        log.info("DeepSeek LLM provider model={} baseUrl={}", properties.model(), baseUrl);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static String trimSlash(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
