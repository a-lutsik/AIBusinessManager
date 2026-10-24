package com.cadence.app.web;

import com.cadence.platform.tenancy.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one MASTER response policy for the console API (PRODUCT_PLAN §5: a master has no access to pricing or revenue).
 *
 * <p>Every {@code /api/app/**} response body written for anyone but the OWNER is turned into a JSON tree and each
 * property whose name contains a money word ({@link #MONEY_WORDS}, case-insensitive) is set to {@code null}, at any
 * depth. The key stays, so clients see "not available" rather than a missing field, and the value is never {@code 0},
 * which would read as a real price. The module records keep money as primitive {@code int}, so a typed redacted copy
 * would need a parallel DTO for every view; redacting the serialized tree here covers every current and future view
 * on this path, fail-closed: a new money field is hidden unless it is named unlike money.
 *
 * <p>Per-resource rules (own specialist, own visits, own clients) stay with the handlers and services; this class only
 * decides what a non-owner may see of an allowed response.
 */
@RestControllerAdvice
public class MasterViewPolicy implements ResponseBodyAdvice<Object> {

    /** Property-name fragments that mark money: prices, amounts, values, revenue, discounts, minor units. */
    static final List<String> MONEY_WORDS = List.of("price", "amount", "value", "revenue", "discount", "minor");

    private static final String APP_API = "/api/app";

    private final JsonMapper jsonMapper;

    public MasterViewPolicy(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        if (body == null || body instanceof ProblemDetail || !isAppApi(request) || !redacts(ActorContext.current().orElse(null))) {
            return body;
        }
        if (!AbstractJacksonHttpMessageConverter.class.isAssignableFrom(selectedConverterType)) {
            // Only JSON bodies can be redacted; anything else would leave the policy unchecked, so refuse it.
            throw new IllegalStateException("MASTER response on " + APP_API + " must be JSON, got " + selectedConverterType.getName());
        }
        return redact(jsonMapper.valueToTree(body));
    }

    /** Everyone except the OWNER is redacted, including a request without an actor (fail-closed). */
    static boolean redacts(ActorContext.Actor actor) {
        return actor == null || !actor.isOwner();
    }

    static boolean isMoneyKey(String propertyName) {
        String name = propertyName.toLowerCase(Locale.ROOT);
        for (String word : MONEY_WORDS) {
            if (name.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Sets every money property of {@code node} (recursively, in objects and arrays) to null; returns the same node. */
    static JsonNode redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> money = new ArrayList<>();
            for (Map.Entry<String, JsonNode> property : object.properties()) {
                if (isMoneyKey(property.getKey())) {
                    money.add(property.getKey());
                } else {
                    redact(property.getValue());
                }
            }
            money.forEach(object::putNull);
        } else if (node != null && node.isArray()) {
            for (JsonNode element : node) {
                redact(element);
            }
        }
        return node;
    }

    /**
     * Decided on the route pattern the request was dispatched to, not on the raw URI, so path tricks that still map
     * to an {@code /api/app} handler (matrix parameters, encoding) cannot step around the policy.
     */
    private static boolean isAppApi(ServerHttpRequest request) {
        String path = null;
        if (request instanceof ServletServerHttpRequest servlet) {
            HttpServletRequest raw = servlet.getServletRequest();
            if (raw.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String pattern) {
                path = pattern;
            }
        }
        if (path == null) {
            path = request.getURI().getPath();
        }
        return path.equals(APP_API) || path.startsWith(APP_API + "/");
    }
}
