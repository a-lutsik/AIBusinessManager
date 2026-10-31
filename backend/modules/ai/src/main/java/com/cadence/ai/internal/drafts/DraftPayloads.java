package com.cadence.ai.internal.drafts;

import com.cadence.ai.api.ChatResponse;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.ai.AiText;
import com.cadence.platform.error.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The versioned payload of {@code ai_draft} (ADR 0002 §4.1). {@code summary} is for the owner's card and holds real names,
 * so a payload never reaches the LLM. Instants are ISO UTC strings.
 */
public final class DraftPayloads {

    private static final Logger log = LoggerFactory.getLogger(DraftPayloads.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final int VERSION = 1;
    public static final String BOOKING = "draft_booking";
    public static final String RESCHEDULE = "draft_reschedule";
    public static final String CANCEL = "draft_cancel";
    /** What stands for a retired client, as in the CRM's own {@code display_name}. */
    public static final String ANONYMIZED = "anonymized";
    private static final int MAX_REASON_CHARS = 500;

    private DraftPayloads() {
    }

    public sealed interface Payload permits Booking, Reschedule, Cancel {
        int v();

        String type();

        /** The client the draft is for; a retired client makes it stale and is how its drafts are found to scrub. */
        UUID clientId();
    }

    public record Expected(String serviceStart, AppointmentStatus status) {
    }

    public record Booking(
            int v,
            String type,
            UUID clientId,
            UUID specialistId,
            UUID serviceId,
            String serviceStart,
            BookingSummary summary
    ) implements Payload {
    }

    public record BookingSummary(
            String clientName,
            String specialistName,
            String serviceName,
            int durationMinutes,
            int priceMinor,
            String currencyCode,
            String localStart
    ) {
    }

    public record Reschedule(
            int v,
            String type,
            UUID clientId,
            UUID appointmentId,
            String newStart,
            Expected expected,
            RescheduleSummary summary
    ) implements Payload {
    }

    public record RescheduleSummary(
            String clientName,
            String specialistName,
            String serviceName,
            String localFrom,
            String localTo
    ) {
    }

    public record Cancel(
            int v,
            String type,
            UUID clientId,
            UUID appointmentId,
            AppointmentStatus target,
            String reason,
            Expected expected,
            CancelSummary summary
    ) implements Payload {
    }

    public record CancelSummary(String clientName, String specialistName, String serviceName, String localStart) {
    }

    public static String write(Payload payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Draft payload cannot be serialised", e);
        }
    }

    /** 409 {@code DRAFT_INVALID} for a payload of an unknown type or version. */
    public static Payload parse(String toolName, String json) {
        Class<? extends Payload> type = switch (toolName) {
            case BOOKING -> Booking.class;
            case RESCHEDULE -> Reschedule.class;
            case CANCEL -> Cancel.class;
            default -> throw invalid();
        };
        try {
            Payload payload = MAPPER.readValue(json, type);
            if (payload.v() != VERSION) {
                throw invalid();
            }
            return payload;
        } catch (JsonProcessingException e) {
            throw invalid();
        }
    }

    /**
     * What the audit trail keeps of a payload: ids, type and times, no {@code summary} and no {@code reason}. Audit rows outlive
     * the client and are never rewritten, so no name may enter them (as in {@code ClientMergeService}).
     */
    public static String forAudit(String payloadJson) {
        return withoutPersonalData(payloadJson, false);
    }

    /** The payload with the client's name and the reason gone, for a retired client ({@code ClientDataScrubber}). */
    public static String scrubbed(String payloadJson) {
        return withoutPersonalData(payloadJson, true);
    }

    private static String withoutPersonalData(String payloadJson, boolean keepSummary) {
        try {
            if (!(MAPPER.readTree(payloadJson) instanceof ObjectNode root)) {
                return "{}";
            }
            root.remove("reason");
            if (!keepSummary) {
                root.remove("summary");
            } else if (root.get("summary") instanceof ObjectNode summary) {
                summary.put("clientName", ANONYMIZED);
            }
            return root.toString();
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** {@code restore} puts the names back into the reason, which is stored in ref form. */
    public static ChatResponse.DraftAction toAction(AiDraftStore.DraftRecord draft, UnaryOperator<String> restore) {
        return new ChatResponse.DraftAction(
                draft.id(),
                draft.toolName(),
                draft.status().name(),
                draft.createdAt(),
                draft.expiresAt(),
                draft.resolvedAt(),
                draft.appliedEntityId(),
                draft.failureCode(),
                draft.failureMessage(),
                summary(draft, restore)
        );
    }

    private static ChatResponse.DraftSummary summary(AiDraftStore.DraftRecord draft, UnaryOperator<String> restore) {
        Payload payload;
        try {
            payload = parse(draft.toolName(), draft.payload());
        } catch (DomainException e) {
            log.warn("Draft payload is unreadable: draft={}, type={}", draft.id(), draft.toolName());
            return null;
        }
        return switch (payload) {
            case Booking b -> new ChatResponse.DraftSummary(
                    b.summary().clientName(), b.summary().specialistName(), b.summary().serviceName(),
                    b.summary().durationMinutes(), b.summary().priceMinor(), b.summary().currencyCode(),
                    b.summary().localStart(), null, null, null, null);
            case Reschedule r -> new ChatResponse.DraftSummary(
                    r.summary().clientName(), r.summary().specialistName(), r.summary().serviceName(),
                    null, null, null, null, r.summary().localFrom(), r.summary().localTo(), null, null);
            case Cancel c -> new ChatResponse.DraftSummary(
                    c.summary().clientName(), c.summary().specialistName(), c.summary().serviceName(),
                    null, null, null, c.summary().localStart(), null, null,
                    c.target() == AppointmentStatus.CANCELLED_BY_CLIENT ? "client" : "business",
                    c.reason() == null ? null : AiText.truncate(restore.apply(c.reason()), MAX_REASON_CHARS));
        };
    }

    private static DomainException invalid() {
        return DomainException.conflict("DRAFT_INVALID", "Draft payload cannot be applied");
    }
}
