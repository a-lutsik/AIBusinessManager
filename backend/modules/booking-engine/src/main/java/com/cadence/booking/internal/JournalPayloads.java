package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentEventType;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The one place that writes and reads {@code appointment_event.payload} (ADR 0001 §1). JSON through Jackson, never by
 * string concatenation; every payload written carries {@code "v":2}. Instants are ISO-8601 UTC
 * ({@link Instant#toString()}), ids are UUID strings, statuses are {@link AppointmentStatus#name()}.
 *
 * <p>Reading accepts what the journal holds after V13: CREATED must be v2 (V13 back-filled the old ones); status and
 * RESCHEDULED events written before V13 had the same {@code from}/{@code to} fields without {@code v} and are read
 * as they are.
 */
final class JournalPayloads {

    static final int VERSION = 2;
    static final String MERGE = "MERGE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JournalPayloads() {
    }

    /** A typed payload; which one belongs to which event type is checked by {@link #requireFits}. */
    sealed interface Payload permits Created, StatusChange, Rescheduled, ClientChanged {
    }

    /** CREATED: the visit's facts at creation. */
    record Created(
            UUID specialistId,
            UUID serviceId,
            UUID clientId,
            AppointmentStatus status,
            Instant serviceStart,
            Instant serviceEnd,
            int priceMinor,
            int discountMinor,
            int durationMinutes,
            String currency,
            String serviceName,
            String source
    ) implements Payload {
        static Created of(AppointmentView row) {
            return new Created(
                    row.specialistId(), row.serviceId(), row.clientId(), row.status(), row.serviceStart(),
                    row.serviceEnd(), row.priceSnapshot(), row.discountAmount(), row.durationSnapshot(),
                    row.currencyCode(), row.serviceNameSnapshot(), row.source()
            );
        }
    }

    /** CONFIRMED, COMPLETED, NO_SHOW, CANCELLED: status {@code from} to {@code to}; {@code correction} for an owner fix. */
    record StatusChange(AppointmentStatus from, AppointmentStatus to, boolean correction) implements Payload {
    }

    /** RESCHEDULED: service start {@code from} to {@code to}; the end is {@code to} plus the CREATED duration. */
    record Rescheduled(Instant from, Instant to) implements Payload {
    }

    /** CLIENT_CHANGED: the visit moved from client {@code from} to {@code to} ({@code reason} MERGE). */
    record ClientChanged(UUID from, UUID to, String reason) implements Payload {
    }

    /** The event type a status change is journaled as. */
    static AppointmentEventType eventTypeFor(AppointmentStatus target) {
        return switch (target) {
            case CONFIRMED -> AppointmentEventType.CONFIRMED;
            case COMPLETED -> AppointmentEventType.COMPLETED;
            case NO_SHOW -> AppointmentEventType.NO_SHOW;
            case CANCELLED_BY_CLIENT, CANCELLED_BY_BUSINESS -> AppointmentEventType.CANCELLED;
            case PENDING -> throw new IllegalArgumentException("No visit moves back to PENDING");
        };
    }

    /** Rejects a payload that does not belong to {@code type} (a programming error, never data). */
    static void requireFits(AppointmentEventType type, Payload payload) {
        boolean fits = switch (type) {
            case CREATED -> payload instanceof Created;
            case CONFIRMED, COMPLETED, NO_SHOW, CANCELLED ->
                    payload instanceof StatusChange s && eventTypeFor(s.to()) == type;
            case RESCHEDULED -> payload instanceof Rescheduled;
            case CLIENT_CHANGED -> payload instanceof ClientChanged;
            case MASTER_CHANGED, SERVICE_CHANGED, PRICE_CHANGED, REMINDER_SENT -> false;
        };
        if (!fits) {
            throw new IllegalArgumentException("Payload " + payload.getClass().getSimpleName() + " does not fit " + type);
        }
    }

    static String toJson(Payload payload) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("v", VERSION);
        switch (payload) {
            case Created c -> {
                node.put("specialistId", c.specialistId().toString());
                node.put("serviceId", c.serviceId().toString());
                node.put("clientId", c.clientId().toString());
                node.put("status", c.status().name());
                node.put("serviceStart", c.serviceStart().toString());
                node.put("serviceEnd", c.serviceEnd().toString());
                node.put("priceMinor", c.priceMinor());
                node.put("discountMinor", c.discountMinor());
                node.put("durationMinutes", c.durationMinutes());
                node.put("currency", c.currency());
                node.put("serviceName", c.serviceName());
                node.put("source", c.source());
            }
            case StatusChange s -> {
                node.put("from", s.from().name());
                node.put("to", s.to().name());
                if (s.correction()) {
                    node.put("correction", true);
                }
            }
            case Rescheduled r -> {
                node.put("from", r.from().toString());
                node.put("to", r.to().toString());
            }
            case ClientChanged c -> {
                node.put("from", c.from().toString());
                node.put("to", c.to().toString());
                node.put("reason", c.reason());
            }
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Journal payload cannot be written", ex);
        }
    }

    /**
     * Reads the payload of an event of {@code type}. Fails with {@link IllegalStateException} for anything the fold
     * cannot trust: unparsable JSON, a CREATED that is not v2, a missing field.
     */
    static Payload parse(AppointmentEventType type, String json) {
        JsonNode node;
        try {
            node = json == null ? null : MAPPER.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unreadable " + type + " payload", ex);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("Missing " + type + " payload");
        }
        return switch (type) {
            case CREATED -> {
                if (node.path("v").asInt(0) != VERSION) {
                    throw new IllegalStateException("CREATED payload is not v" + VERSION + ": " + json);
                }
                yield new Created(
                        uuid(node, "specialistId"),
                        uuid(node, "serviceId"),
                        uuid(node, "clientId"),
                        status(node, "status"),
                        instant(node, "serviceStart"),
                        instant(node, "serviceEnd"),
                        integer(node, "priceMinor"),
                        integer(node, "discountMinor"),
                        integer(node, "durationMinutes"),
                        text(node, "currency"),
                        text(node, "serviceName"),
                        text(node, "source")
                );
            }
            case CONFIRMED, COMPLETED, NO_SHOW, CANCELLED ->
                    new StatusChange(status(node, "from"), status(node, "to"), node.path("correction").asBoolean(false));
            case RESCHEDULED -> new Rescheduled(instant(node, "from"), instant(node, "to"));
            case CLIENT_CHANGED -> new ClientChanged(uuid(node, "from"), uuid(node, "to"), text(node, "reason"));
            case MASTER_CHANGED, SERVICE_CHANGED, PRICE_CHANGED, REMINDER_SENT ->
                    throw new IllegalStateException(type + " has no payload reader");
        };
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Journal payload misses '" + field + "'");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(required(node, field).asText());
    }

    private static Instant instant(JsonNode node, String field) {
        return Instant.parse(required(node, field).asText());
    }

    private static AppointmentStatus status(JsonNode node, String field) {
        return AppointmentStatus.valueOf(required(node, field).asText());
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.canConvertToInt()) {
            throw new IllegalStateException("Journal payload field '" + field + "' is not an int");
        }
        return value.asInt();
    }
}
