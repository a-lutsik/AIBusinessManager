package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.drafts.DraftPayloads;
import com.cadence.ai.internal.drafts.DraftPreflight;
import com.cadence.ai.internal.drafts.DraftWriter;
import com.cadence.ai.internal.llm.ToolSpec;
import com.cadence.ai.internal.privacy.RefKind;
import com.cadence.booking.api.AppointmentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@code reason} is the only model text on an owner's card: stored as the model wrote it, with refs, at most 500 characters, plain
 * text. The card restores it on every read, so erasing a retired client's name from the refs erases it here too.
 */
@Component
class DraftCancelTool implements AiTool {

    private static final int MAX_REASON_CHARS = 500;
    private static final ToolSpec SPEC = ToolArgs.spec(DraftPayloads.CANCEL,
            "Proposes cancelling a pending or confirmed visit as a draft the owner confirms; nothing changes until then.",
            """
            {"type":"object","additionalProperties":false,"required":["visitRef","initiatedBy"],"properties":{
              "visitRef":{"type":"string","description":"VISIT_n"},
              "initiatedBy":{"type":"string","enum":["business","client"]},
              "reason":{"type":"string","maxLength":500}}}
            """);

    private final DraftPreflight preflight;
    private final DraftWriter writer;

    DraftCancelTool(DraftPreflight preflight, DraftWriter writer) {
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
        String initiatedBy = ToolArgs.text(args, "initiatedBy");
        AppointmentStatus target = switch (initiatedBy) {
            case "business" -> AppointmentStatus.CANCELLED_BY_BUSINESS;
            case "client" -> AppointmentStatus.CANCELLED_BY_CLIENT;
            default -> throw new ToolException(ToolException.INVALID_ARGUMENTS, "initiatedBy must be business or client");
        };
        String reason = ToolArgs.optionalText(args, "reason");
        if (reason != null && reason.length() > MAX_REASON_CHARS) {
            // Never cut: CLIENT_12 cut to CLIENT_1 would show the owner another client's name.
            throw new ToolException(ToolException.INVALID_ARGUMENTS, "reason is limited to " + MAX_REASON_CHARS + " characters");
        }
        DraftPayloads.Cancel payload = preflight.cancel(context, visit, target, reason == null || reason.isEmpty() ? null : reason);
        DraftPayloads.CancelSummary card = payload.summary();
        return writer.save(context, payload, card.serviceName() + ", " + card.localStart(),
                new ToolResults.DraftRefs(null, null, null, ToolArgs.text(args, "visitRef"), null, null, initiatedBy));
    }
}
