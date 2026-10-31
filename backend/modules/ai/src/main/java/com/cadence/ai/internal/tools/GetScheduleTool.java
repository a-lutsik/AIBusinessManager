package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.AiProperties;
import com.cadence.ai.internal.llm.ToolSpec;
import com.cadence.ai.internal.privacy.FreeTextMasker;
import com.cadence.ai.internal.privacy.RefKind;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** The visits of up to seven days; this is an entry point of client refs into the conversation. */
@Component
class GetScheduleTool implements AiTool {

    private static final int MAX_VISITS = 200;
    /** A row is never shorter than this in JSON, so rows past the limit are cut before their refs are registered. */
    private static final int MIN_ROW_CHARS = 125;
    private static final ToolSpec SPEC = ToolArgs.spec("get_schedule",
            "Visits between two local dates (at most 7 days), optionally of one master.",
            """
            {"type":"object","additionalProperties":false,"required":["dateFrom","dateTo"],"properties":{
              "dateFrom":{"type":"string","description":"YYYY-MM-DD"},
              "dateTo":{"type":"string","description":"YYYY-MM-DD, inclusive"},
              "masterRef":{"type":"string","description":"MASTER_n"}}}
            """);

    private final BookingService booking;
    private final FreeTextMasker masker;
    private final int maxRows;

    GetScheduleTool(BookingService booking, FreeTextMasker masker, AiProperties properties) {
        this.booking = booking;
        this.masker = masker;
        this.maxRows = Math.min(MAX_VISITS, properties.maxToolResultChars() / MIN_ROW_CHARS);
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResults.Result execute(ToolContext context, JsonNode args) {
        ToolArgs.DateRange range = ToolArgs.dateRange(args);
        UUID master = context.resolveOptional(args, "masterRef", RefKind.MASTER);
        ZoneId zone = context.zone();
        List<AppointmentView> visits = booking.calendar(
                range.from().atStartOfDay(zone).toInstant(),
                range.to().plusDays(1).atStartOfDay(zone).toInstant(),
                master);
        boolean truncated = visits.size() > maxRows;
        List<AppointmentView> shown = truncated ? visits.subList(0, maxRows) : visits;
        List<ToolResults.VisitRow> rows = context.inOneTransaction(() -> shown.stream()
                .map(visit -> new ToolResults.VisitRow(
                        context.visitRef(visit),
                        context.masterRef(visit.specialistId(), "master"),
                        context.ref(RefKind.CLIENT, visit.clientId(), visit.clientDisplayName()),
                        context.label(masker, visit.serviceNameSnapshot()),
                        ToolArgs.local(visit.serviceStart(), zone),
                        ToolArgs.local(visit.serviceEnd(), zone),
                        visit.status().name()))
                .toList());
        return new ToolResults.Schedule(rows, truncated);
    }
}
