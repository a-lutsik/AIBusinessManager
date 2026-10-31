package com.cadence.app.web;

import com.cadence.catalog.api.MasterServiceView;
import com.cadence.catalog.api.ScheduleExceptionView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.platform.error.DomainException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads catalog write bodies strictly, after the owner check. The views keep numbers and flags as primitives, and plain
 * binding would turn a value the owner did not send into 0 or false (a free service, a deactivated master) and cut
 * 80.5 down to 80. Here a required number or flag has to be there with its own type, whole numbers included;
 * {@code INVALID_REQUEST_BODY} otherwise. What the value may be is the catalog's rule.
 */
final class CatalogRequests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CatalogRequests() {
    }

    static ServiceView service(JsonNode body) {
        requireObject(body);
        requireWhole(body, "durationMinutes", "priceMinor", "bufferBeforeMinutes", "bufferAfterMinutes");
        requireFlags(body, "active", "publicVisible");
        return read(body, ServiceView.class);
    }

    static SpecialistView specialist(JsonNode body) {
        requireObject(body);
        requireFlags(body, "active");
        return read(body, SpecialistView.class);
    }

    static MasterServiceView matrixRow(JsonNode body) {
        requireObject(body);
        requireFlags(body, "offered");
        checkWhole(body, "durationMinutesOverride", "priceMinorOverride", "bufferBeforeMinutesOverride", "bufferAfterMinutesOverride");
        return read(body, MasterServiceView.class);
    }

    /** A null element stays null: the catalog refuses it as an interval. */
    static List<WeeklyInterval> weeklySchedule(JsonNode body) {
        if (body == null || !body.isArray()) {
            throw unreadable();
        }
        List<WeeklyInterval> intervals = new ArrayList<>();
        for (JsonNode element : body) {
            if (element.isNull()) {
                intervals.add(null);
            } else {
                requireObject(element);
                requireWhole(element, "weekday", "startMinute", "endMinute");
                intervals.add(read(element, WeeklyInterval.class));
            }
        }
        return intervals;
    }

    static ScheduleExceptionView exception(JsonNode body) {
        requireObject(body);
        checkWhole(body, "startMinute", "endMinute");
        // A date only as text: a number would be read as days since 1970.
        JsonNode date = body.get("date");
        if (date != null && !date.isNull() && !date.isString()) {
            throw unreadable();
        }
        return read(body, ScheduleExceptionView.class);
    }

    private static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw unreadable();
        }
    }

    private static void requireWhole(JsonNode body, String... fields) {
        for (String field : fields) {
            if (!isWhole(body.get(field))) {
                throw unreadable();
            }
        }
    }

    /** Optional numbers: absent and null are fine, a value has to be whole. */
    private static void checkWhole(JsonNode body, String... fields) {
        for (String field : fields) {
            JsonNode value = body.get(field);
            if (value != null && !value.isNull() && !isWhole(value)) {
                throw unreadable();
            }
        }
    }

    private static boolean isWhole(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt();
    }

    private static void requireFlags(JsonNode body, String... fields) {
        for (String field : fields) {
            JsonNode value = body.get(field);
            if (value == null || !value.isBoolean()) {
                throw unreadable();
            }
        }
    }

    private static <T> T read(JsonNode body, Class<T> type) {
        // Plain binding reads "" as a null id, which creates a row instead of refusing the body.
        JsonNode id = body.get("id");
        if (id != null && !id.isNull() && !(id.isString() && !id.asString().isEmpty())) {
            throw unreadable();
        }
        try {
            return MAPPER.treeToValue(body, type);
        } catch (JacksonException e) {
            throw unreadable();
        }
    }

    private static DomainException unreadable() {
        return DomainException.badRequest("INVALID_REQUEST_BODY", "The request body cannot be read.");
    }
}
