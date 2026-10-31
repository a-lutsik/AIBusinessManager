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
class DraftRescheduleTool implements AiTool {

    private static final ToolSpec SPEC = ToolArgs.spec(DraftPayloads.RESCHEDULE,
            "Proposes moving a pending or confirmed visit as a draft the owner confirms; nothing changes until then.",
            """
            {"type":"object","additionalProperties":false,"required":["visitRef","newStart"],"properties":{
              "visitRef":{"type":"string","description":"VISIT_n"},
              "newStart":{"type":"string","description":"local YYYY-MM-DDTHH:MM"}}}
            """);

    private final DraftPreflight preflight;
    private final DraftWriter writer;

    DraftRescheduleTool(DraftPreflight preflight, DraftWriter writer) {
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
        UUID visit = context.resolve(args, "visitRef", RefKind.VISIT);
        Instant newStart = ToolArgs.localTime(args, "newStart", context.zone());
        DraftPayloads.Reschedule payload = preflight.reschedule(context, visit, newStart);
        DraftPayloads.RescheduleSummary card = payload.summary();
        return writer.save(context, payload, card.serviceName() + ", " + card.localTo(),
                new ToolResults.DraftRefs(null, null, null, ToolArgs.text(args, "visitRef"), null,
                        ToolArgs.local(newStart, context.zone()), null));
    }
}
