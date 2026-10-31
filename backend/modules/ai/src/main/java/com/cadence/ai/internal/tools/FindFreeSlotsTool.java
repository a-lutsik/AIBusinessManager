package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.llm.ToolSpec;
import com.cadence.ai.internal.privacy.RefKind;
import com.cadence.booking.api.BookingService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class FindFreeSlotsTool implements AiTool {

    private static final int MAX_SLOTS = 100;
    private static final ToolSpec SPEC = ToolArgs.spec("find_free_slots",
            "Free local start times of a service with a master between two local dates (at most 7 days).",
            """
            {"type":"object","additionalProperties":false,"required":["masterRef","serviceRef","dateFrom","dateTo"],"properties":{
              "masterRef":{"type":"string","description":"MASTER_n"},
              "serviceRef":{"type":"string","description":"SERVICE_n"},
              "dateFrom":{"type":"string","description":"YYYY-MM-DD"},
              "dateTo":{"type":"string","description":"YYYY-MM-DD, inclusive"}}}
            """);

    private final BookingService booking;

    FindFreeSlotsTool(BookingService booking) {
        this.booking = booking;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResults.Result execute(ToolContext context, JsonNode args) {
        UUID master = context.resolve(args, "masterRef", RefKind.MASTER);
        UUID service = context.resolve(args, "serviceRef", RefKind.SERVICE);
        ToolArgs.DateRange range = ToolArgs.dateRange(args);
        List<Instant> slots = booking.freeSlots(master, service, range.from(), range.to());
        return new ToolResults.FreeSlots(
                slots.stream().limit(MAX_SLOTS).map(slot -> ToolArgs.local(slot, context.zone())).toList(),
                slots.size() > MAX_SLOTS);
    }
}
