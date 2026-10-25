package com.cadence.app.web;

import com.cadence.booking.api.AppointmentView;
import com.cadence.crm.api.ClientView;
import com.cadence.platform.phone.PhoneShape;
import com.cadence.retention.api.TrustView;
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
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ser.std.StdSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one MASTER response policy for the console API (PRODUCT_PLAN §5: a master has no access to pricing or revenue).
 *
 * <p>Every {@code /api/app/**} response body written for anyone but the OWNER is turned into a JSON tree and each
 * property whose name contains a money word ({@link #MONEY_WORDS}, case-insensitive) is set to {@code null}, at any
 * depth. The key stays, so clients see "not available" rather than a missing field, and the value is never {@code 0},
 * which would read as a real price. The module records keep money as primitive {@code int}, so a typed redacted copy
 * would need a parallel DTO for every view; redacting the serialized tree here covers every current and future view
 * on this path. It is fail-closed for callers without the OWNER role (including no actor) and for non-JSON
 * converters, but name-based for fields: a money field named without a money word would not be hidden. That gap is
 * guarded by {@code MoneyFieldClassifierTest}, which fails until every numeric response component is either matched
 * by {@link #MONEY_WORDS} or explicitly allow-listed as not money.
 *
 * <p>Per-resource rules (own specialist, own visits, own clients) stay with the handlers and services; this class only
 * decides what a non-owner may see of an allowed response.
 */
@RestControllerAdvice
public class MasterViewPolicy implements ResponseBodyAdvice<Object> {

    /** Property-name fragments that mark money: prices, amounts, values, revenue, discounts, minor units. */
    static final List<String> MONEY_WORDS = List.of("price", "amount", "value", "revenue", "discount", "minor");

    /**
     * TD-18, the client part of the policy: for each client-bearing type, the properties a non-owner may see, applied
     * by type wherever the type occurs in a response (client list and card, calendar, dashboard, today, visit detail,
     * write responses). Every other property of the type is written as {@code null} with its key kept, so a field
     * added to the type later stays hidden until it is listed here. Money properties are absent from the lists and
     * are nulled by the money rule as well.
     *
     * <p>A master sees a shared client's id and display name only: no phone, owner notes or tags, Telegram chat,
     * locale, marketing consent, or tenant-wide counters and first/last visit. A visit keeps its own facts, the
     * client's display name and trust level and whether it is hidden from the master until the owner confirms it
     * (decision A; the booking service then shows only the typed name), but not the client phone and not the typed
     * {@code enteredName}, which can hold the owner's "typed / stored" hint or a phone. A trust assessment (decision B)
     * keeps its level, the override level and the number-free {@code reasonCodes}; the {@code reasons} texts carry
     * salon-wide counters and {@code overrideReason} is the owner's free text, so both are hidden.
     */
    static final Map<Class<?>, Set<String>> CLIENT_FIELD_ALLOW_LIST = Map.of(
            ClientView.class, Set.of("id", "displayName"),
            AppointmentView.class, Set.of(
                    "id", "specialistId", "serviceId", "clientId", "status",
                    "serviceStart", "serviceEnd", "occupiedStart", "occupiedEnd",
                    "serviceNameSnapshot", "durationSnapshot", "bufferBeforeSnapshot", "bufferAfterSnapshot",
                    "currencyCode", "note", "source", "clientDisplayName", "trustLevel", "clientHiddenFromMaster"
            ),
            TrustView.class, Set.of(
                    "clientId", "level", "overrideLevel", "effectiveLevel", "reasonCodes", "new", "low"
            )
    );

    /**
     * A shown name is hidden as well when it is the client's phone (a client created without a name is stored with
     * the phone as its name): name property and the phone property it is checked against, per type.
     */
    private static final Map<Class<?>, String[]> NAME_AND_PHONE = Map.of(
            ClientView.class, new String[]{"displayName", "normalizedPhone"},
            AppointmentView.class, new String[]{"clientDisplayName", "clientPhone"}
    );

    private static final String APP_API = "/api/app";

    private final JsonMapper jsonMapper;
    /** {@link #jsonMapper} plus the per-type client allow-list; used only for non-owner bodies. */
    private final JsonMapper restrictedMapper;

    public MasterViewPolicy(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        SimpleModule clientFields = new SimpleModule("master-client-fields");
        CLIENT_FIELD_ALLOW_LIST.forEach((type, allowed) ->
                addAllowListSerializer(clientFields, type, allowed, NAME_AND_PHONE.get(type), jsonMapper));
        this.restrictedMapper = jsonMapper.rebuild().addModule(clientFields).build();
    }

    private static <T> void addAllowListSerializer(SimpleModule module, Class<T> type, Set<String> allowed,
                                                   String[] nameAndPhone, JsonMapper plain) {
        module.addSerializer(type, new AllowListSerializer<>(type, allowed, nameAndPhone, plain));
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
        // Client allow-list while serializing (it needs the Java type), then the money rule on the tree.
        return redact(restrictedMapper.valueToTree(body));
    }

    /** Whether {@code name} shows {@code phone}; the rule lives in {@link PhoneShape}, shared with the master search. */
    static boolean showsPhone(String name, String phone) {
        return PhoneShape.showsPhone(name, phone);
    }

    /** Writes a value of one client-bearing type with only its allow-listed properties; the rest become null. */
    private static final class AllowListSerializer<T> extends StdSerializer<T> {

        private final Set<String> allowed;
        private final String[] nameAndPhone;
        /** The mapper without this module, so the value's own serialization does not recurse into this serializer. */
        private final JsonMapper plain;

        AllowListSerializer(Class<T> type, Set<String> allowed, String[] nameAndPhone, JsonMapper plain) {
            super(type);
            this.allowed = allowed;
            this.nameAndPhone = nameAndPhone;
            this.plain = plain;
        }

        @Override
        public void serialize(T value, JsonGenerator gen, SerializationContext context) {
            ObjectNode node = plain.valueToTree(value);
            String phone = null;
            if (nameAndPhone != null && node.get(nameAndPhone[1]) instanceof JsonNode p && p.isString()) {
                phone = p.asString();
            }
            List<String> hidden = new ArrayList<>();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (!allowed.contains(property.getKey())) {
                    hidden.add(property.getKey());
                }
            }
            hidden.forEach(node::putNull);
            if (nameAndPhone != null && node.get(nameAndPhone[0]) instanceof JsonNode n && n.isString()
                    && showsPhone(n.asString(), phone)) {
                node.putNull(nameAndPhone[0]);
            }
            gen.writeTree(node);
        }
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
