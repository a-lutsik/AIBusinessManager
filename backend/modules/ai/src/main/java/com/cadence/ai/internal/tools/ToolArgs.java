package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.llm.ToolSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;

/** Reading untrusted tool arguments and the tenant-local time format {@code YYYY-MM-DDTHH:MM} (ADR 0002 §2.3). */
public final class ToolArgs {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final int MAX_RANGE_DAYS = 7;

    record DateRange(LocalDate from, LocalDate to) {
    }

    private ToolArgs() {
    }

    static ToolSpec spec(String name, String description, String schemaJson) {
        try {
            return new ToolSpec(name, description, MAPPER.readTree(schemaJson));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The trimmed text of a required string argument. */
    static String text(JsonNode args, String name) {
        String value = optionalText(args, name);
        if (value == null) {
            throw new ToolException(ToolException.INVALID_ARGUMENTS, name + " is required");
        }
        return value;
    }

    /** The trimmed text of a string argument, null when it is missing or JSON null. */
    static String optionalText(JsonNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new ToolException(ToolException.INVALID_ARGUMENTS, name + " must be a string");
        }
        return node.textValue().trim();
    }

    /** {@code dateFrom}..{@code dateTo}, both inclusive, at most seven days. */
    static DateRange dateRange(JsonNode args) {
        LocalDate from = date(args, "dateFrom");
        LocalDate to = date(args, "dateTo");
        if (to.isBefore(from)) {
            throw new ToolException(ToolException.INVALID_ARGUMENTS, "dateTo is before dateFrom");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new ToolException(ToolException.RANGE_TOO_LARGE, "The range is limited to " + MAX_RANGE_DAYS + " days");
        }
        return new DateRange(from, to);
    }

    private static LocalDate date(JsonNode args, String name) {
        try {
            return LocalDate.parse(text(args, name));
        } catch (DateTimeParseException e) {
            throw new ToolException(ToolException.INVALID_ARGUMENTS, name + " must be YYYY-MM-DD");
        }
    }

    static Instant localTime(JsonNode args, String name, ZoneId zone) {
        try {
            return LocalDateTime.parse(text(args, name), LOCAL).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new ToolException(ToolException.INVALID_ARGUMENTS, name + " must be YYYY-MM-DDTHH:MM");
        }
    }

    /** Null stays null. */
    public static String local(Instant at, ZoneId zone) {
        return at == null ? null : LOCAL.format(at.atZone(zone));
    }
}
