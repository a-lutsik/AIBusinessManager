package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.llm.ToolSpec;
import com.cadence.ai.internal.privacy.FreeTextMasker;
import com.cadence.ai.internal.privacy.NameIndex;
import com.cadence.ai.internal.privacy.PseudonymRegistry;
import com.cadence.ai.internal.privacy.RefKind;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.platform.ai.AiText;
import com.cadence.retention.api.RetentionService;
import com.cadence.retention.api.TrustView;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Only for a client ref already in the conversation (the owner's message, the schedule) whose client is still active. Notes and tags reach the LLM only
 * when the owner allowed it, and then masked. Trust is {@code compute}, not {@code assess}: a read must not write trust state.
 */
@Component
class GetClientProfileTool implements AiTool {

    static final String WITHHELD = "[withheld by tenant setting]";
    private static final int MAX_VISITS = 10;
    private static final int MAX_NOTES_CHARS = 1000;
    private static final int MAX_TAGS_CHARS = 256;
    private static final ToolSpec SPEC = ToolArgs.spec("get_client_profile",
            "Visit counters, trust, upcoming and recent visits, and notes of a client already referenced in this conversation."
                    + " Money is in minor units.",
            """
            {"type":"object","additionalProperties":false,"required":["clientRef"],"properties":{
              "clientRef":{"type":"string","description":"CLIENT_n"}}}
            """);

    private final CrmService crm;
    private final BookingService booking;
    private final RetentionService retention;
    private final FreeTextMasker masker;
    private final PseudonymRegistry registry;
    private final Clock clock;

    GetClientProfileTool(
            CrmService crm,
            BookingService booking,
            RetentionService retention,
            FreeTextMasker masker,
            PseudonymRegistry registry,
            Clock clock
    ) {
        this.crm = crm;
        this.booking = booking;
        this.retention = retention;
        this.masker = masker;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResults.Result execute(ToolContext context, JsonNode args) {
        String clientRef = ToolArgs.text(args, "clientRef");
        UUID clientId = context.resolve(args, "clientRef", RefKind.CLIENT);
        ClientView client = crm.findActiveById(clientId)
                .orElseThrow(() -> new ToolException(ToolException.UNKNOWN_REF, "The client no longer exists"));
        TrustView trust = retention.compute(clientId);
        Instant now = clock.instant();
        List<AppointmentView> upcoming = new ArrayList<>();
        List<AppointmentView> recent = new ArrayList<>();
        // clientHistory is oldest first
        for (AppointmentView visit : booking.clientHistory(clientId)) {
            boolean open = visit.status() == AppointmentStatus.PENDING || visit.status() == AppointmentStatus.CONFIRMED;
            if (open && visit.serviceStart().isAfter(now)) {
                if (upcoming.size() < MAX_VISITS) {
                    upcoming.add(visit);
                }
            } else {
                recent.addFirst(visit);
            }
        }
        List<AppointmentView> latest = recent.subList(0, Math.min(recent.size(), MAX_VISITS));
        List<List<ToolResults.ProfileVisit>> rows = context.inOneTransaction(
                () -> List.of(rows(context, upcoming), rows(context, latest)));
        // After the refs above, so the names of these masters are masked too.
        NameIndex names = context.notesToLlm() ? registry.nameIndex(context.conversationId()) : null;
        return new ToolResults.ClientProfile(
                clientRef,
                client.completedVisitCount(),
                client.noShowCount(),
                client.lateCancelCount(),
                ToolArgs.local(client.firstVisitAt(), context.zone()),
                ToolArgs.local(client.lastVisitAt(), context.zone()),
                client.completedValueMinor(),
                context.tenant().currencyCode(),
                new ToolResults.Trust(trust.effectiveLevel(), trust.reasonCodes()),
                rows.get(0),
                rows.get(1),
                names == null ? WITHHELD : masked(client.notes(), names, MAX_NOTES_CHARS),
                names == null ? WITHHELD : masked(client.tags(), names, MAX_TAGS_CHARS));
    }

    private List<ToolResults.ProfileVisit> rows(ToolContext context, List<AppointmentView> visits) {
        return visits.stream()
                .map(visit -> new ToolResults.ProfileVisit(
                        context.visitRef(visit),
                        context.masterRef(visit.specialistId(), "master"),
                        context.label(masker, visit.serviceNameSnapshot()),
                        ToolArgs.local(visit.serviceStart(), context.zone()),
                        visit.status().name()))
                .toList();
    }

    private String masked(String text, NameIndex names, int maxChars) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return AiText.truncate(masker.mask(text, names), maxChars);
    }
}
