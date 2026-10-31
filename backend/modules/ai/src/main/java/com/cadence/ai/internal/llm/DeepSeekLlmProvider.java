package com.cadence.ai.internal.llm;

import com.cadence.ai.internal.AiProperties;
import com.cadence.platform.error.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek chat completions (OpenAI-compatible format) with function calling. Non-thinking mode only: the tool loop
 * does not round-trip {@code reasoning_content}, so {@link #validate()} refuses any other thinking setting (TD-41).
 */
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
        if (isThinkingEnabled()) {
            throw new IllegalStateException(
                    "app.ai.deepseek.thinking must be 'disabled' (was '" + thinkingType
                            + "'): the AI tool loop with thinking is not supported (TD-41)"
            );
        }
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        String body = serialise(request);
        String json;
        try {
            json = client.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
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
        return parse(json);
    }

    private String serialise(LlmRequest request) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        payload.put("stream", false);
        ArrayNode messages = payload.putArray("messages");
        for (LlmMessage message : request.messages()) {
            messages.add(toJson(message));
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = payload.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", spec.name());
                function.put("description", spec.description());
                if (spec.parametersSchema() != null && !spec.parametersSchema().isNull()) {
                    function.set("parameters", spec.parametersSchema());
                }
            }
            // tool_choice is only meaningful (and only accepted by OpenAI-compatible APIs) alongside tools.
            payload.put("tool_choice", request.toolChoice() == ToolChoice.NONE ? "none" : "auto");
        }
        payload.put("max_tokens", request.maxCompletionTokens());
        ObjectNode thinking = payload.putObject("thinking");
        thinking.put("type", thinkingType);
        if (isThinkingEnabled() && reasoningEffort != null && !reasoningEffort.isBlank()) {
            thinking.put("reasoning_effort", reasoningEffort);
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the LLM request", e);
        }
    }

    private static ObjectNode toJson(LlmMessage message) {
        ObjectNode node = MAPPER.createObjectNode();
        switch (message) {
            case LlmMessage.System m -> {
                node.put("role", "system");
                node.put("content", m.content());
            }
            case LlmMessage.User m -> {
                node.put("role", "user");
                node.put("content", m.content());
            }
            case LlmMessage.Assistant m -> {
                node.put("role", "assistant");
                if (m.content() == null) {
                    node.putNull("content");
                } else {
                    node.put("content", m.content());
                }
                if (m.hasToolCalls()) {
                    ArrayNode calls = node.putArray("tool_calls");
                    for (ToolCall call : m.toolCalls()) {
                        ObjectNode c = calls.addObject();
                        c.put("id", call.id());
                        c.put("type", "function");
                        ObjectNode function = c.putObject("function");
                        function.put("name", call.name());
                        function.put("arguments", call.argumentsJson());
                    }
                }
            }
            case LlmMessage.ToolResult m -> {
                node.put("role", "tool");
                node.put("tool_call_id", m.toolCallId());
                node.put("content", m.contentJson());
            }
        }
        return node;
    }

    private static LlmResponse parse(String json) {
        JsonNode root;
        try {
            root = json == null || json.isBlank() ? null : MAPPER.readTree(json);
        } catch (Exception e) {
            throw DomainException.badGateway("LLM_UNAVAILABLE", "DeepSeek returned an unreadable completion");
        }
        JsonNode choice = root == null ? null : root.path("choices").path(0);
        if (choice == null || choice.isMissingNode() || !choice.isObject()) {
            throw DomainException.badGateway("LLM_UNAVAILABLE", "DeepSeek returned an empty completion");
        }
        JsonNode message = choice.path("message");
        String content = text(message.path("content"));
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            String id = call.path("id").asText("");
            if (name.isBlank() || id.isBlank()) {
                throw DomainException.badGateway("LLM_UNAVAILABLE", "DeepSeek returned a malformed tool call");
            }
            JsonNode arguments = function.path("arguments");
            toolCalls.add(new ToolCall(id, name, arguments.isTextual() ? arguments.asText() : text(arguments)));
        }
        if (content == null && toolCalls.isEmpty()) {
            throw DomainException.badGateway("LLM_UNAVAILABLE", "DeepSeek returned an empty completion");
        }
        JsonNode usage = root.path("usage");
        return new LlmResponse(
                new LlmMessage.Assistant(content, toolCalls),
                finishReason(choice.path("finish_reason").asText("")),
                new Usage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0))
        );
    }

    /** Text of a node, or null when absent, null or blank. */
    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.isTextual() ? node.asText() : node.toString();
        return value.isBlank() ? null : value;
    }

    private static FinishReason finishReason(String value) {
        return switch (value) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.LENGTH;
            default -> FinishReason.OTHER;
        };
    }

    private boolean isThinkingEnabled() {
        return thinkingType != null && !thinkingType.isBlank() && !"disabled".equalsIgnoreCase(thinkingType);
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
