package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.drafts.DraftPayloads;
import com.cadence.ai.internal.drafts.DraftPreflight;
import com.cadence.ai.internal.drafts.DraftWriter;
import com.cadence.ai.internal.llm.ToolSpec;
import com.cadence.ai.internal.privacy.RefKind;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
class DraftBookingTool implements AiTool {

    private static final ToolSpec SPEC = ToolArgs.spec(DraftPayloads.BOOKING,
            "Proposes a new visit of an existing client as a draft the owner confirms; nothing changes until then.",
            """
            {"type":"object","additionalProperties":false,"required":["clientRef","masterRef","serviceRef","start"],"properties":{
              "clientRef":{"type":"string","description":"CLIENT_n"},
              "masterRef":{"type":"string","description":"MASTER_n"},
              "serviceRef":{"type":"string","description":"SERVICE_n"},
              "start":{"type":"string","description":"local YYYY-MM-DDTHH:MM"}}}
            """);

    private final DraftPreflight preflight;
    private final DraftWriter writer;

    DraftBookingTool(DraftPreflight preflight, DraftWriter writer) {
        this.preflight = preflight;
        this.writer = writer;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResults.Result execute(ToolContext context, JsonNode args) {
        context.assertDraftAllowed();
        UUID client = context.resolve(args, "clientRef", RefKind.CLIENT);
        UUID master = context.resolve(args, "masterRef", RefKind.MASTER);
        UUID service = context.resolve(args, "serviceRef", RefKind.SERVICE);
        Instant start = ToolArgs.localTime(args, "start", context.zone());
        DraftPayloads.Booking payload = preflight.booking(context, client, master, service, start);
        DraftPayloads.BookingSummary card = payload.summary();
        return writer.save(context, payload, card.serviceName() + ", " + card.localStart(),
                new ToolResults.DraftRefs(ToolArgs.text(args, "clientRef"), ToolArgs.text(args, "masterRef"),
                        ToolArgs.text(args, "serviceRef"), null, ToolArgs.local(start, context.zone()), null, null));
    }
}
