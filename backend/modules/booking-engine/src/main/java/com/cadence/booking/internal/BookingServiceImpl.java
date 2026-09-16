package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.booking.api.PublicBookingResult;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.Offering;
import com.cadence.catalog.api.ScheduleExceptionView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.outbox.OutboxPublisher;
import com.cadence.platform.phone.PhoneNormalizer;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.retention.api.RetentionService;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingServiceImpl implements BookingService {

    private final CatalogService catalogService;
    private final CrmService crmService;
    private final RetentionService retentionService;
    private final AppointmentRepository appointments;
    private final TenantDirectory tenants;
    private final OutboxPublisher outbox;
    private final AuditLogger auditLogger;
    private final OwnerTaskService ownerTasks;
    private final PhoneNormalizer phones;
    private final Clock clock;

    public BookingServiceImpl(
            CatalogService catalogService,
            CrmService crmService,
            RetentionService retentionService,
            AppointmentRepository appointments,
            TenantDirectory tenants,
            OutboxPublisher outbox,
            AuditLogger auditLogger,
            OwnerTaskService ownerTasks,
            PhoneNormalizer phones,
            Clock clock
    ) {
        this.catalogService = catalogService;
        this.crmService = crmService;
        this.retentionService = retentionService;
        this.appointments = appointments;
        this.tenants = tenants;
        this.outbox = outbox;
        this.auditLogger = auditLogger;
        this.ownerTasks = ownerTasks;
        this.phones = phones;
        this.clock = clock;
    }

    @Override
    public String moduleName() {
        return "booking-engine";
    }

    @Override
    @Transactional(readOnly = true)
    public List<Instant> freeSlots(UUID specialistId, UUID serviceId, LocalDate from, LocalDate to) {
        return freeSlots(specialistId, serviceId, from, to, null);
    }

    private List<Instant> freeSlots(UUID specialistId, UUID serviceId, LocalDate from, LocalDate to, UUID excludeAppointmentId) {
        Offering offering = requireOffering(specialistId, serviceId);
        TenantRecord tenant = tenants.requireById(TenantContext.require());
        BookingRulesView rules = catalogService.rules();
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        List<SlotCalculator.WeeklyWindow> weekly = catalogService.weeklySchedule(specialistId).stream()
                .map(w -> new SlotCalculator.WeeklyWindow(w.weekday(), w.startMinute(), w.endMinute()))
                .toList();
        List<SlotCalculator.ExceptionDay> exceptions = catalogService.exceptions(specialistId, from, to).stream()
                .map(e -> new SlotCalculator.ExceptionDay(e.date(), e.kind(), e.startMinute(), e.endMinute()))
                .toList();
        Instant rangeStart = from.atStartOfDay(zone).toInstant();
        Instant rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<SlotCalculator.OccupiedInterval> occupying = appointments.occupying(specialistId, rangeStart, rangeEnd, excludeAppointmentId);
        return SlotCalculator.freeServiceStarts(
                zone,
                from,
                to,
                weekly,
                exceptions,
                occupying,
                offering.durationMinutes(),
                offering.bufferBeforeMinutes(),
                offering.bufferAfterMinutes(),
                rules.slotStepMinutes(),
                clock.instant(),
                rules.minNoticeMinutes(),
                rules.horizonDays()
        );
    }

    @Override
    @Transactional
    public AppointmentView create(CreateBookingCommand command) {
        return persist(command, false).appointment();
    }

    @Override
    @Transactional
    public PublicBookingResult createPublic(CreateBookingCommand command) {
        return persist(command, true);
    }

    private PublicBookingResult persist(CreateBookingCommand command, boolean issueToken) {
        Offering offering = requireOffering(command.specialistId(), command.serviceId());
        TenantRecord tenant = tenants.requireById(TenantContext.require());
        BookingRulesView rules = catalogService.rules();
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        Instant start = command.serviceStart();
        SlotCalculator.OccupiedInterval occupied = SlotCalculator.occupiedFor(
                start, offering.durationMinutes(), offering.bufferBeforeMinutes(), offering.bufferAfterMinutes()
        );
        if (!command.skipNoticeAndHorizon()) {
            Instant now = clock.instant();
            if (start.isBefore(now.plus(Duration.ofMinutes(rules.minNoticeMinutes())))) {
                throw DomainException.conflict("MIN_NOTICE", "Slot is too soon to book");
            }
            if (start.isAfter(now.plus(Duration.ofDays(rules.horizonDays())))) {
                throw DomainException.conflict("BEYOND_HORIZON", "Slot is beyond the booking horizon");
            }
            LocalDate day = start.atZone(zone).toLocalDate();
            List<Instant> free = freeSlots(command.specialistId(), command.serviceId(), day, day);
            if (free.stream().noneMatch(start::equals)) {
                throw DomainException.conflict("SLOT_OCCUPIED", "Slot is not available");
            }
        } else {
            List<SlotCalculator.OccupiedInterval> occupying = appointments.occupying(
                    command.specialistId(), occupied.start().minusSeconds(1), occupied.end().plusSeconds(1), null
            );
            if (occupying.stream().anyMatch(occupied::overlaps)) {
                throw DomainException.conflict("SLOT_OCCUPIED", "Slot is not available");
            }
        }

        UUID clientId = command.clientId();
        String enteredName = command.displayName();
        if (clientId == null) {
            String region = tenant.getCountryCode();
            String e164 = phones.toE164(command.phone(), region);
            ClientView client = crmService.findOrCreate(
                    e164,
                    enteredName == null ? e164 : enteredName,
                    command.locale(),
                    command.marketingConsent(),
                    command.consentVersion()
            );
            clientId = client.id();
            if (enteredName != null && !enteredName.equals(client.displayName())) {
                enteredName = enteredName + " / " + client.displayName();
            }
        }

        AppointmentStatus status = command.statusOverride();
        if (status == null) {
            boolean pending = retentionService.requiresConfirmation(clientId);
            status = pending ? AppointmentStatus.PENDING : AppointmentStatus.CONFIRMED;
        }

        assertMasterOwns(command.specialistId());

        UUID id = UUID.randomUUID();
        AppointmentView row = new AppointmentView(
                id,
                command.specialistId(),
                command.serviceId(),
                clientId,
                status,
                start,
                start.plus(Duration.ofMinutes(offering.durationMinutes())),
                occupied.start(),
                occupied.end(),
                offering.serviceName(),
                offering.priceMinor(),
                offering.durationMinutes(),
                offering.bufferBeforeMinutes(),
                offering.bufferAfterMinutes(),
                tenant.getCurrencyCode(),
                0,
                null,
                enteredName,
                command.note(),
                command.source() == null ? "CONSOLE" : command.source(),
                enteredName,
                null
        );
        appointments.insert(row);
        appointments.insertEvent(id, "CREATED", "{\"status\":\"" + status + "\"}");
        outbox.publish("BOOKING_CREATED", "appointment", id, "{\"status\":\"" + status + "\"}");
        auditLogger.record(
                ActorContext.current().map(ActorContext.Actor::actorId).orElse("public"),
                ActorContext.current().map(a -> a.role().name()).orElse("PUBLIC"),
                "appointment.create",
                "appointment",
                id,
                null,
                "{\"status\":\"" + status + "\"}",
                command.source(),
                null
        );
        String raw = null;
        if (issueToken) {
            raw = BookingTokens.raw();
            appointments.insertToken(id, BookingTokens.hash(raw), clock.instant().plus(Duration.ofDays(365)));
            String manage = "/b/" + id + "?token=" + raw;
            String copy = status == AppointmentStatus.PENDING
                    ? "Your visit is waiting for confirmation. " + manage
                    : "Your visit is confirmed. " + manage;
            ownerTasks.create(
                    "NEW_BOOKING",
                    "New booking — send confirmation manually",
                    copy,
                    copy,
                    manage,
                    id,
                    clientId
            );
        }
        return new PublicBookingResult(enrich(row), raw, raw == null ? null : "/b/" + id + "?token=" + raw);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentView> calendar(Instant from, Instant to, UUID specialistId) {
        UUID filter = specialistId;
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor != null && actor.isMaster()) {
            filter = actor.specialistId();
        }
        return appointments.calendar(from, to, filter).stream().map(this::enrich).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppointmentView> find(UUID appointmentId) {
        return appointments.find(appointmentId).map(this::enrich).map(this::assertCanRead);
    }

    @Override
    @Transactional
    public AppointmentView transition(UUID appointmentId, AppointmentStatus target, String note) {
        AppointmentView current = appointments.find(appointmentId)
                .orElseThrow(() -> DomainException.notFound("APPOINTMENT_NOT_FOUND", "Visit not found"));
        assertCanWrite(current);
        AppointmentStatusMachine.assertTransition(current.status(), target);
        AppointmentView updated = withStatus(current, target, note == null ? current.note() : note);
        appointments.updateStatusAndTimes(updated);
        String event = switch (target) {
            case CONFIRMED -> "CONFIRMED";
            case COMPLETED -> "COMPLETED";
            case NO_SHOW -> "NO_SHOW";
            case CANCELLED_BY_CLIENT, CANCELLED_BY_BUSINESS -> "CANCELLED";
            default -> target.name();
        };
        appointments.insertEvent(appointmentId, event, "{\"from\":\"" + current.status() + "\",\"to\":\"" + target + "\"}");
        if (target == AppointmentStatus.COMPLETED) {
            crmService.recordCompletedVisit(
                    current.clientId(),
                    current.serviceStart(),
                    current.priceSnapshot() - current.discountAmount()
            );
        }
        if (target == AppointmentStatus.NO_SHOW) {
            crmService.recordNoShow(current.clientId());
        }
        if ((target == AppointmentStatus.CANCELLED_BY_CLIENT || target == AppointmentStatus.CANCELLED_BY_BUSINESS)
                && current.status() == AppointmentStatus.CONFIRMED) {
            BookingRulesView rules = catalogService.rules();
            Instant now = clock.instant();
            if (current.serviceStart().isAfter(now)
                    && Duration.between(now, current.serviceStart()).toHours() < rules.lateCancellationHours()) {
                crmService.recordLateCancel(current.clientId());
            }
        }
        auditLogger.record(
                ActorContext.current().map(ActorContext.Actor::actorId).orElse("system"),
                ActorContext.current().map(a -> a.role().name()).orElse("SYSTEM"),
                "appointment.transition",
                "appointment",
                appointmentId,
                "{\"status\":\"" + current.status() + "\"}",
                "{\"status\":\"" + target + "\"}",
                "console",
                null
        );
        return enrich(updated);
    }

    @Override
    @Transactional
    public AppointmentView reschedule(UUID appointmentId, Instant newStart) {
        AppointmentView current = appointments.find(appointmentId)
                .orElseThrow(() -> DomainException.notFound("APPOINTMENT_NOT_FOUND", "Visit not found"));
        assertCanWrite(current);
        if (current.status() != AppointmentStatus.PENDING && current.status() != AppointmentStatus.CONFIRMED) {
            throw DomainException.conflict("INVALID_TRANSITION", "Only pending or confirmed visits can be moved");
        }
        SlotCalculator.OccupiedInterval occupied = SlotCalculator.occupiedFor(
                newStart, current.durationSnapshot(), current.bufferBeforeSnapshot(), current.bufferAfterSnapshot()
        );
        ZoneId zone = ZoneId.of(tenants.requireById(TenantContext.require()).getTimezone());
        LocalDate day = newStart.atZone(zone).toLocalDate();
        List<Instant> free = freeSlots(current.specialistId(), current.serviceId(), day, day, current.id());
        if (free.stream().noneMatch(newStart::equals)) {
            throw DomainException.conflict("SLOT_OCCUPIED", "New slot is not available");
        }
        AppointmentView updated = new AppointmentView(
                current.id(),
                current.specialistId(),
                current.serviceId(),
                current.clientId(),
                current.status(),
                newStart,
                newStart.plus(Duration.ofMinutes(current.durationSnapshot())),
                occupied.start(),
                occupied.end(),
                current.serviceNameSnapshot(),
                current.priceSnapshot(),
                current.durationSnapshot(),
                current.bufferBeforeSnapshot(),
                current.bufferAfterSnapshot(),
                current.currencyCode(),
                current.discountAmount(),
                current.amountReceived(),
                current.enteredName(),
                current.note(),
                current.source(),
                current.clientDisplayName(),
                current.clientPhone()
        );
        appointments.updateStatusAndTimes(updated);
        appointments.insertEvent(appointmentId, "RESCHEDULED", "{\"from\":\"" + current.serviceStart() + "\",\"to\":\"" + newStart + "\"}");
        return enrich(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppointmentView> findByAccessToken(String rawToken) {
        String hash = BookingTokens.hash(rawToken);
        UUID tenantId = appointments.tenantIdByTokenHash(hash).orElse(null);
        if (tenantId == null) {
            return Optional.empty();
        }
        try (var ignored = TenantContext.open(tenantId)) {
            return appointments.appointmentIdByTokenHash(hash).flatMap(appointments::find).map(this::enrich);
        }
    }

    @Override
    @Transactional
    public AppointmentView cancelByAccessToken(String rawToken, boolean byClient) {
        AppointmentView view = findByAccessToken(rawToken)
                .orElseThrow(() -> DomainException.notFound("TOKEN_INVALID", "Booking link is invalid"));
        UUID tenantId = appointments.tenantIdByTokenHash(BookingTokens.hash(rawToken)).orElseThrow();
        try (var ignored = TenantContext.open(tenantId)) {
            AppointmentStatus target = byClient ? AppointmentStatus.CANCELLED_BY_CLIENT : AppointmentStatus.CANCELLED_BY_BUSINESS;
            return transition(view.id(), target, view.note());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String ics(UUID appointmentId) {
        AppointmentView appointment = find(appointmentId)
                .orElseThrow(() -> DomainException.notFound("APPOINTMENT_NOT_FOUND", "Visit not found"));
        String specialist = catalogService.findSpecialist(appointment.specialistId())
                .map(SpecialistView::displayName)
                .orElse("");
        String tenantName = tenants.requireById(TenantContext.require()).getDisplayName();
        return IcsFactory.render(appointment, specialist, tenantName);
    }

    @Override
    @Transactional(readOnly = true)
    public String icsByAccessToken(String rawToken) {
        String hash = BookingTokens.hash(rawToken);
        UUID tenantId = appointments.tenantIdByTokenHash(hash)
                .orElseThrow(() -> DomainException.notFound("TOKEN_INVALID", "Booking link is invalid"));
        try (var ignored = TenantContext.open(tenantId)) {
            UUID appointmentId = appointments.appointmentIdByTokenHash(hash)
                    .orElseThrow(() -> DomainException.notFound("TOKEN_INVALID", "Booking link is invalid"));
            return ics(appointmentId);
        }
    }

    @Override
    public Optional<String> rawTokenFor(UUID appointmentId) {
        return Optional.empty();
    }

    private Offering requireOffering(UUID specialistId, UUID serviceId) {
        Offering offering = catalogService.offering(specialistId, serviceId)
                .orElseThrow(() -> DomainException.conflict("SERVICE_NOT_OFFERED", "This specialist does not offer the service"));
        if (!offering.serviceActive() || !offering.offered()) {
            throw DomainException.conflict("SERVICE_INACTIVE", "Service is not available");
        }
        return offering;
    }

    private AppointmentView enrich(AppointmentView row) {
        ClientView client = crmService.findById(row.clientId()).orElse(null);
        if (client == null) {
            return row;
        }
        return new AppointmentView(
                row.id(),
                row.specialistId(),
                row.serviceId(),
                row.clientId(),
                row.status(),
                row.serviceStart(),
                row.serviceEnd(),
                row.occupiedStart(),
                row.occupiedEnd(),
                row.serviceNameSnapshot(),
                row.priceSnapshot(),
                row.durationSnapshot(),
                row.bufferBeforeSnapshot(),
                row.bufferAfterSnapshot(),
                row.currencyCode(),
                row.discountAmount(),
                row.amountReceived(),
                row.enteredName(),
                row.note(),
                row.source(),
                client.displayName(),
                client.normalizedPhone()
        );
    }

    private AppointmentView withStatus(AppointmentView current, AppointmentStatus status, String note) {
        return new AppointmentView(
                current.id(),
                current.specialistId(),
                current.serviceId(),
                current.clientId(),
                status,
                current.serviceStart(),
                current.serviceEnd(),
                current.occupiedStart(),
                current.occupiedEnd(),
                current.serviceNameSnapshot(),
                current.priceSnapshot(),
                current.durationSnapshot(),
                current.bufferBeforeSnapshot(),
                current.bufferAfterSnapshot(),
                current.currencyCode(),
                current.discountAmount(),
                current.amountReceived(),
                current.enteredName(),
                note,
                current.source(),
                current.clientDisplayName(),
                current.clientPhone()
        );
    }

    private AppointmentView assertCanRead(AppointmentView appointment) {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor != null && actor.isMaster() && !appointment.specialistId().equals(actor.specialistId())) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Master cannot access another specialist's visit");
        }
        return appointment;
    }

    private void assertCanWrite(AppointmentView appointment) {
        assertCanRead(appointment);
    }

    private void assertMasterOwns(UUID specialistId) {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor != null && actor.isMaster() && !specialistId.equals(actor.specialistId())) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Master cannot book for another specialist");
        }
    }
}
