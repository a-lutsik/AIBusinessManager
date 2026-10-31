package com.cadence.ai.internal.drafts;

import com.cadence.ai.internal.tools.ToolArgs;
import com.cadence.ai.internal.tools.ToolContext;
import com.cadence.ai.internal.tools.ToolException;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.Offering;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The advisory check before a draft exists (ADR 0002 §4.2), over public read ports with the engine's own refusal codes;
 * the engine stays the only truth at confirmation. A refusal means no draft. The owner's card ({@code summary}) is built
 * here from the resolved entities, so nothing the LLM wrote can appear on it except a cancel's {@code reason}.
 */
@Component
public class DraftPreflight {

    private final BookingService booking;
    private final CatalogService catalog;
    private final CrmService crm;
    private final Clock clock;

    public DraftPreflight(BookingService booking, CatalogService catalog, CrmService crm, Clock clock) {
        this.booking = booking;
        this.catalog = catalog;
        this.crm = crm;
        this.clock = clock;
    }

    public DraftPayloads.Booking booking(ToolContext context, UUID clientId, UUID specialistId, UUID serviceId, Instant start) {
        Offering offering = catalog.offering(specialistId, serviceId)
                .filter(Offering::offered)
                .orElseThrow(() -> new ToolException("SERVICE_NOT_OFFERED", "This master does not offer the service"));
        if (!offering.serviceActive()) {
            throw new ToolException("SERVICE_INACTIVE", "The service is not available");
        }
        BookingRulesView rules = context.rules();
        Instant now = clock.instant();
        if (start.isBefore(now.plus(Duration.ofMinutes(rules.minNoticeMinutes())))) {
            throw new ToolException("MIN_NOTICE", "The slot is too soon to book");
        }
        if (start.isAfter(now.plus(Duration.ofDays(rules.horizonDays())))) {
            throw new ToolException("BEYOND_HORIZON", "The slot is beyond the booking horizon");
        }
        assertFree(context, specialistId, serviceId, start);
        ClientView client = crm.findActiveById(clientId)
                .orElseThrow(() -> new ToolException(ToolException.UNKNOWN_REF, "The client no longer exists"));
        return new DraftPayloads.Booking(DraftPayloads.VERSION, DraftPayloads.BOOKING, clientId, specialistId, serviceId,
                start.toString(),
                new DraftPayloads.BookingSummary(
                        client.displayName(),
                        context.masterName(specialistId),
                        offering.serviceName(),
                        offering.durationMinutes(),
                        offering.priceMinor(),
                        context.tenant().currencyCode(),
                        ToolArgs.local(start, context.zone())));
    }

    /** Its own old slot is not excluded (TD-38): the engine's confirmation is the exact check. */
    public DraftPayloads.Reschedule reschedule(ToolContext context, UUID visitId, Instant newStart) {
        AppointmentView visit = draftableVisit(visitId);
        assertFree(context, visit.specialistId(), visit.serviceId(), newStart);
        return new DraftPayloads.Reschedule(DraftPayloads.VERSION, DraftPayloads.RESCHEDULE, visit.clientId(), visit.id(),
                newStart.toString(),
                expected(visit),
                new DraftPayloads.RescheduleSummary(
                        visit.clientDisplayName(),
                        context.masterName(visit.specialistId()),
                        visit.serviceNameSnapshot(),
                        ToolArgs.local(visit.serviceStart(), context.zone()),
                        ToolArgs.local(newStart, context.zone())));
    }

    /** {@code reason} is in ref form and already limited by the caller. */
    public DraftPayloads.Cancel cancel(ToolContext context, UUID visitId, AppointmentStatus target, String reason) {
        AppointmentView visit = draftableVisit(visitId);
        return new DraftPayloads.Cancel(DraftPayloads.VERSION, DraftPayloads.CANCEL, visit.clientId(), visit.id(), target,
                reason,
                expected(visit),
                new DraftPayloads.CancelSummary(
                        visit.clientDisplayName(),
                        context.masterName(visit.specialistId()),
                        visit.serviceNameSnapshot(),
                        ToolArgs.local(visit.serviceStart(), context.zone())));
    }

    private AppointmentView draftableVisit(UUID visitId) {
        AppointmentView visit = booking.find(visitId)
                .orElseThrow(() -> new ToolException("APPOINTMENT_NOT_FOUND", "The visit no longer exists"));
        if (visit.status() != AppointmentStatus.PENDING && visit.status() != AppointmentStatus.CONFIRMED) {
            throw new ToolException(ToolException.NOT_DRAFTABLE, "Only pending or confirmed visits can be changed");
        }
        if (crm.findActiveById(visit.clientId()).isEmpty()) {
            throw new ToolException(ToolException.UNKNOWN_REF, "The client of this visit no longer exists");
        }
        return visit;
    }

    private void assertFree(ToolContext context, UUID specialistId, UUID serviceId, Instant start) {
        LocalDate day = start.atZone(context.zone()).toLocalDate();
        if (!booking.freeSlots(specialistId, serviceId, day, day).contains(start)) {
            throw new ToolException("SLOT_OCCUPIED", "The slot is not available");
        }
    }

    private static DraftPayloads.Expected expected(AppointmentView visit) {
        return new DraftPayloads.Expected(visit.serviceStart().toString(), visit.status());
    }
}
