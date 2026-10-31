package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.AiProperties;
import com.cadence.ai.internal.llm.LlmMessage;
import com.cadence.ai.internal.llm.ToolCall;
import com.cadence.ai.internal.llm.ToolSpec;
import com.cadence.platform.error.DomainException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs the tool calls of a turn: an error never ends the turn, the LLM reads {@code {"error":{code,message}}} (ADR 0002
 * §2.2). A port's refusal goes as its code with a fixed message, since its text is not ours to show. Results longer than
 * {@code max-tool-result-chars} lose rows from their lists and say {@code "truncated":true}. Logs carry codes and names of
 * tools only, never arguments or results.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final String TOOL_FAILED = "TOOL_FAILED";
    private static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";

    /** What the LLM reads for one call; {@code errorCode} is null when it succeeded. */
    public record Execution(String resultJson, String errorCode) {
    }

    private final Map<String, AiTool> tools;
    private final List<ToolSpec> specs;
    private final int maxResultChars;
    private final ObjectMapper mapper = new ObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ToolRegistry(List<AiTool> tools, AiProperties properties) {
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(t -> t.spec().name(), Function.identity()));
        this.specs = tools.stream().map(AiTool::spec).toList();
        this.maxResultChars = properties.maxToolResultChars();
    }

    public List<ToolSpec> specs() {
        return specs;
    }

    public Execution execute(ToolContext context, ToolCall call) {
        AiTool tool = tools.get(call.name());
        if (tool == null) {
            log.warn("LLM asked for an unknown tool: conversation={}", context.conversationId());
            return error(UNKNOWN_TOOL, "No such tool");
        }
        try {
            JsonNode args = arguments(tool, call);
            return fit(mapper.valueToTree(tool.execute(context, args)), context, call.name());
        } catch (ToolException e) {
            log.warn("Tool refused: conversation={}, tool={}, code={}", context.conversationId(), call.name(), e.code());
            return error(e.code(), e.getMessage());
        } catch (DomainException e) {
            log.warn("Tool refused by a port: conversation={}, tool={}, code={}", context.conversationId(), call.name(), e.code());
            return error(e.code(), "Refused: " + e.code());
        } catch (RuntimeException e) {
            log.error("Tool failed: conversation={}, tool={}", context.conversationId(), call.name(), e);
            return error(TOOL_FAILED, "The tool failed");
        }
    }

    /**
     * The model's message as it may be sent back: a call whose arguments are not a JSON object gets {@code {}}, as a provider
     * rejects the next request otherwise. The raw text stays in {@code ai_tool_call}, the call itself is answered with
     * {@code INVALID_ARGUMENTS}.
     */
    public LlmMessage.Assistant forHistory(LlmMessage.Assistant answer) {
        List<ToolCall> calls = answer.toolCalls().stream()
                .map(call -> parsesAsObject(call.argumentsJson()) ? call : new ToolCall(call.id(), call.name(), "{}"))
                .toList();
        return new LlmMessage.Assistant(answer.content(), calls);
    }

    private boolean parsesAsObject(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node != null && node.isObject();
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /** {@code error} for a call that was not run. */
    public Execution refused(String code, String message) {
        return error(code, message);
    }

    /** The arguments as an object holding only properties of the tool's schema ({@code additionalProperties:false}). */
    private JsonNode arguments(AiTool tool, ToolCall call) {
        JsonNode args;
        try {
            args = mapper.readTree(call.argumentsJson());
        } catch (JsonProcessingException e) {
            throw new ToolException(ToolException.INVALID_ARGUMENTS, "Arguments are not valid JSON");
        }
        if (args == null || !args.isObject()) {
            throw new ToolException(ToolException.INVALID_ARGUMENTS, "Arguments must be a JSON object");
        }
        JsonNode allowed = tool.spec().parametersSchema().path("properties");
        for (Iterator<String> names = args.fieldNames(); names.hasNext(); ) {
            if (!allowed.has(names.next())) {
                throw new ToolException(ToolException.INVALID_ARGUMENTS, "Unknown argument");
            }
        }
        return args;
    }

    private Execution error(String code, String message) {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("error").put("code", code).put("message", message);
        return new Execution(root.toString(), code);
    }

    /** Deepest lists lose their last rows first, so a metric keeps its total before its per-master breakdown. */
    private Execution fit(ObjectNode root, ToolContext context, String toolName) {
        String json = root.toString();
        if (json.length() <= maxResultChars) {
            return new Execution(json, null);
        }
        log.warn("Tool result truncated: conversation={}, tool={}, chars={}, limit={}",
                context.conversationId(), toolName, json.length(), maxResultChars);
        root.put("truncated", true);
        for (Deepest found = deepest(root, 0, null); found != null; found = deepest(root, 0, null)) {
            found.list().remove(found.list().size() - 1);
            json = root.toString();
            if (json.length() <= maxResultChars) {
                return new Execution(json, null);
            }
        }
        return error("RESULT_TOO_LARGE", "The result does not fit; narrow the request");
    }

    private static Deepest deepest(JsonNode node, int depth, Deepest best) {
        Deepest found = best;
        if (node instanceof ArrayNode list && !list.isEmpty() && (found == null || depth > found.depth()
                || depth == found.depth() && list.size() > found.list().size())) {
            found = new Deepest(list, depth);
        }
        for (JsonNode child : node) {
            found = deepest(child, depth + 1, found);
        }
        return found;
    }

    private record Deepest(ArrayNode list, int depth) {
    }
}
