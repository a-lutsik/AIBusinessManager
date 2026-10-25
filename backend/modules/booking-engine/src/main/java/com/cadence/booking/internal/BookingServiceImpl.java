package com.cadence.booking.internal;

import com.cadence.booking.api.AccessLinkView;
import com.cadence.booking.api.AppointmentEventType;
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
import com.cadence.platform.i18n.MessageCatalog;
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

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofDays(365);

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
    private final MessageCatalog messages;
    private final ClientMessages clientMessages;

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
            Clock clock,
            MessageCatalog messages
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
        this.messages = messages;
        this.clientMessages = new ClientMessages(messages);
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
        // Before any read or client upsert: a refused master booking has no side effects at all.
        assertMasterOwns(command.specialistId());
        assertMasterKeepsBookingRules(command);
        assertNotSettledBeforeStart(command.statusOverride(), command.serviceStart());
        assertMasterMayBookClient(command.clientId(), command.specialistId());
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
        // The visit stores what was typed, nothing else; the owner's "typed / stored" hint is built when the owner
        // reads it (enrich), so no public or master answer can carry a stored name through this column.
        String enteredName = command.displayName();
        boolean hideClient = false;
        // Locale of the client-facing confirmation text: the stored profile locale wins (set once, first booking),
        // the locale sent with this booking is the hint for a path that never reads the profile.
        String clientLocale = command.locale();
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
            clientLocale = client.preferredLocale() != null ? client.preferredLocale() : command.locale();
            // Decision A: a master's phone booking onto a client outside their scope (known or just created, the
            // master cannot tell) hides that client from the master until the owner confirms the visit.
            hideClient = isMaster() && !appointments.hasActiveVisit(clientId, command.specialistId());
        }

        AppointmentStatus status = command.statusOverride();
        if (hideClient) {
            // Always PENDING, whatever was asked for and whatever the client's trust: neither the status nor a refusal
            // may tell the master whether the phone belonged to an existing client.
            status = AppointmentStatus.PENDING;
        } else if (status == null) {
            boolean pending = retentionService.requiresConfirmation(clientId);
            status = pending ? AppointmentStatus.PENDING : AppointmentStatus.CONFIRMED;
        } else if (status == AppointmentStatus.CONFIRMED && isMaster() && retentionService.requiresConfirmation(clientId)) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "This client's visit must start as pending");
        }

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
                null,
                null,
                hideClient
        );
        appointments.record(row, AppointmentEventType.CREATED, JournalPayloads.Created.of(row));
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
        if (hideClient) {
            String master = catalogService.findSpecialist(command.specialistId())
                    .map(SpecialistView::displayName)
                    .map(name -> name.length() > MAX_NAME_IN_TITLE ? name.substring(0, MAX_NAME_IN_TITLE) : name)
                    .orElse("A master");
            ownerTasks.create(
                    MASTER_PHONE_BOOKING_TASK,
                    messages.forOwner("owner.task.masterPhoneBooking.title", master),
                    messages.forOwner("owner.task.masterPhoneBooking.body"),
                    null,
                    null,
                    id,
                    clientId
            );
        }
        String raw = null;
        if (issueToken) {
            raw = BookingTokens.raw();
            appointments.insertToken(id, BookingTokens.hash(raw), clock.instant().plus(ACCESS_TOKEN_TTL));
            // The raw token reaches the client once (response below); owner tasks carry only a placeholder.
            String copy = clientMessages.bookingConfirmation(status, clientLocale);
            ownerTasks.create(
                    "NEW_BOOKING",
                    messages.forOwner("owner.task.newBooking.title"),
                    copy,
                    copy,
                    null,
                    id,
                    clientId
            );
        }
        AppointmentView result;
        if (issueToken) {
            result = publicView(row);
        } else if (isMaster() && command.clientId() == null) {
            // Phone path: the phone may belong to a client the master does not know; answer with what the master
            // typed, never with a stored name, phone or trust (TD-19). A hidden visit carries no client id either, so
            // a known and a new number get the same answer.
            result = hideClient ? maskedForMaster(row) : withClientShown(row, row.enteredName(), row.enteredName(), null, null);
        } else {
            result = enrich(row);
        }
        return new PublicBookingResult(result, raw, raw == null ? null : managePath(id, raw));
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
    @Transactional(readOnly = true)
    public List<UUID> clientIdsOf(UUID specialistId) {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        UUID filter = actor != null && actor.isMaster() ? actor.specialistId() : specialistId;
        return appointments.clientIdsOf(filter);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentView> clientHistory(UUID clientId) {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        UUID specialistFilter = actor != null && actor.isMaster() ? actor.specialistId() : null;
        // A master's view of the client leaves cancelled visits out, so a cancellation never keeps a client in scope.
        return appointments.byClient(clientId, specialistFilter, specialistFilter != null).stream().map(this::enrich).toList();
    }

    @Override
    @Transactional
    public AppointmentView transition(UUID appointmentId, AppointmentStatus target, String note) {
        return enrich(applyTransition(appointmentId, target, note));
    }

    /** The transition itself; returns the written row without any client data added. */
    private AppointmentView applyTransition(UUID appointmentId, AppointmentStatus target, String note) {
        // Row lock: concurrent changes of one visit are serialised, so the CRM aggregates below apply exactly once.
        AppointmentView current = appointments.findForUpdate(appointmentId)
                .orElseThrow(() -> DomainException.notFound("APPOINTMENT_NOT_FOUND", "Visit not found"));
        assertCanWrite(current);
        AppointmentStatusMachine.assertTransition(current.status(), target);
        if (isStatusCorrection(current.status(), target) && clock.instant().isBefore(current.serviceStart())) {
            // TD-17 legacy: a row settled before the rule existed, while its start was still ahead. The rule stays.
            throw DomainException.conflict("VISIT_NOT_STARTED", "This visit was settled before it started (recorded "
                    + "before that was refused); its outcome can be corrected only after it starts");
        }
        assertNotSettledBeforeStart(target, current.serviceStart());
        if (isMaster() && current.clientHiddenFromMaster() && target == AppointmentStatus.CONFIRMED) {
            // Decision A: only the owner's confirmation reveals the client; same answer as the trust rule below.
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "This client's visit must be confirmed by the owner");
        }
        if (isMaster() && current.status() == AppointmentStatus.PENDING && target == AppointmentStatus.CONFIRMED
                && retentionService.requiresConfirmation(current.clientId())) {
            // Same rule as on create: a master cannot skip the confirmation the retention policy demands of this client.
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "This client's visit must be confirmed by the owner");
        }
        boolean correction = isStatusCorrection(current.status(), target);
        if (correction) {
            assertOwnerMayCorrect();
        }
        // The owner's confirmation clears the decision-A flag; any other change (a cancellation too) keeps it.
        boolean ownerConfirms = target == AppointmentStatus.CONFIRMED && isOwner();
        boolean hidden = current.clientHiddenFromMaster() && !ownerConfirms;
        AppointmentView updated = withStatus(current, target, note == null ? current.note() : note, hidden);
        appointments.record(updated, JournalPayloads.eventTypeFor(target),
                new JournalPayloads.StatusChange(current.status(), target, correction));
        if (current.clientHiddenFromMaster() && ownerConfirms) {
            // The client is now in the master's scope, so the master's other hidden visits with them are revealed too.
            appointments.revealToMaster(current.clientId(), current.specialistId());
        }
        boolean cancelled = target == AppointmentStatus.CANCELLED_BY_CLIENT || target == AppointmentStatus.CANCELLED_BY_BUSINESS;
        if (ownerConfirms || cancelled) {
            // The owner's "confirm the master's phone booking" task is done once the visit is confirmed or cancelled.
            ownerTasks.completeForAppointment(appointmentId, MASTER_PHONE_BOOKING_TASK);
        }
        int valueMinor = current.priceSnapshot() - current.discountAmount();
        if (correction && current.status() == AppointmentStatus.COMPLETED) {
            // COMPLETED -> NO_SHOW: take the visit back out of the aggregates, then count the no-show.
            AppointmentRepository.CompletedRange remaining = appointments.completedRangeExcluding(current.clientId(), appointmentId);
            crmService.revertCompletedVisit(current.clientId(), valueMinor, remaining.first(), remaining.last());
        }
        if (correction && current.status() == AppointmentStatus.NO_SHOW) {
            // NO_SHOW -> COMPLETED: the earlier no-show was a mistake.
            crmService.revertNoShow(current.clientId());
        }
        if (target == AppointmentStatus.COMPLETED) {
            crmService.recordCompletedVisit(current.clientId(), current.serviceStart(), valueMinor);
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
                correction ? "appointment.status_correction" : "appointment.transition",
                "appointment",
                appointmentId,
                "{\"status\":\"" + current.status() + "\"}",
                "{\"status\":\"" + target + "\"}",
                "console",
                null
        );
        return updated;
    }

    /** Owner fix of a settled outcome: NO_SHOW to COMPLETED or COMPLETED to NO_SHOW. */
    private static boolean isStatusCorrection(AppointmentStatus from, AppointmentStatus to) {
        return (from == AppointmentStatus.NO_SHOW && to == AppointmentStatus.COMPLETED)
                || (from == AppointmentStatus.COMPLETED && to == AppointmentStatus.NO_SHOW);
    }

    private static void assertOwnerMayCorrect() {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor == null || !actor.isOwner()) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Only the owner can correct a completed or no-show visit");
        }
    }

    @Override
    @Transactional
    public int reassignClient(UUID from, UUID to) {
        return appointments.reassignClient(from, to);
    }

    @Override
    @Transactional
    public AppointmentView reschedule(UUID appointmentId, Instant newStart) {
        return enrich(applyReschedule(appointmentId, newStart));
    }

    /** The move itself; returns the written row without any client data added. */
    private AppointmentView applyReschedule(UUID appointmentId, Instant newStart) {
        AppointmentView current = appointments.findForUpdate(appointmentId)
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
                current.clientPhone(),
                current.trustLevel(),
                current.clientHiddenFromMaster()
        );
        appointments.record(updated, AppointmentEventType.RESCHEDULED,
                new JournalPayloads.Rescheduled(current.serviceStart(), newStart));
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppointmentView> findByAccessToken(String rawToken) {
        return appointments.resolveToken(BookingTokens.hash(rawToken)).flatMap(target -> {
            try (var ignored = TenantContext.open(target.tenantId())) {
                return appointments.find(target.appointmentId()).map(BookingServiceImpl::publicView);
            }
        });
    }

    @Override
    @Transactional
    public AppointmentView cancelByAccessToken(String rawToken, boolean byClient) {
        AppointmentRepository.TokenTarget target = requireToken(rawToken);
        try (var ignored = TenantContext.open(target.tenantId())) {
            AppointmentView view = appointments.find(target.appointmentId()).orElseThrow(BookingServiceImpl::tokenInvalid);
            AppointmentStatus status = byClient ? AppointmentStatus.CANCELLED_BY_CLIENT : AppointmentStatus.CANCELLED_BY_BUSINESS;
            return publicView(applyTransition(view.id(), status, view.note()));
        }
    }

    @Override
    @Transactional
    public AppointmentView rescheduleByAccessToken(String rawToken, Instant newStart) {
        AppointmentRepository.TokenTarget target = requireToken(rawToken);
        try (var ignored = TenantContext.open(target.tenantId())) {
            BookingRulesView rules = catalogService.rules();
            if (!rules.clientRescheduleAllowed()) {
                throw DomainException.forbidden("RESCHEDULE_DISABLED", "Client reschedule is disabled for this business");
            }
            return publicView(applyReschedule(target.appointmentId(), newStart));
        }
    }

    @Override
    @Transactional
    public AppointmentView withdrawConsentByAccessToken(UUID appointmentId, String rawToken) {
        AppointmentRepository.TokenTarget target = requireToken(rawToken);
        if (!target.appointmentId().equals(appointmentId)) {
            throw DomainException.forbidden("TOKEN_INVALID", "Token does not match this visit");
        }
        try (var ignored = TenantContext.open(target.tenantId())) {
            AppointmentView view = appointments.find(target.appointmentId()).orElseThrow(BookingServiceImpl::tokenInvalid);
            crmService.withdrawMarketingConsent(view.clientId(), "public-link");
            return publicView(view);
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
        AppointmentRepository.TokenTarget target = requireToken(rawToken);
        try (var ignored = TenantContext.open(target.tenantId())) {
            return ics(target.appointmentId());
        }
    }

    /** Resolves a live token across tenants; the caller opens the returned tenant scope before tenant reads. */
    private AppointmentRepository.TokenTarget requireToken(String rawToken) {
        return appointments.resolveToken(BookingTokens.hash(rawToken)).orElseThrow(BookingServiceImpl::tokenInvalid);
    }

    private static DomainException tokenInvalid() {
        return DomainException.notFound("TOKEN_INVALID", "Booking link is invalid");
    }

    private static String managePath(UUID appointmentId, String rawToken) {
        return "/b/" + appointmentId + "?token=" + rawToken;
    }

    @Override
    @Transactional
    public AccessLinkView issueAccessLink(UUID appointmentId) {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor == null || !actor.isOwner()) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Only the owner can issue a client access link");
        }
        AppointmentView appointment = appointments.find(appointmentId)
                .orElseThrow(() -> DomainException.notFound("APPOINTMENT_NOT_FOUND", "Visit not found"));
        if (appointment.status() == AppointmentStatus.CANCELLED_BY_CLIENT
                || appointment.status() == AppointmentStatus.CANCELLED_BY_BUSINESS) {
            throw DomainException.conflict("APPOINTMENT_CANCELLED", "Visit is cancelled");
        }
        Instant expiresAt = clock.instant().plus(ACCESS_TOKEN_TTL);
        String raw = BookingTokens.raw();
        appointments.insertToken(appointmentId, BookingTokens.hash(raw), expiresAt);
        // Audit carries no token: only that a link was issued and when it expires.
        auditLogger.record(
                actor.actorId(),
                actor.role().name(),
                "appointment.access_link.issue",
                "appointment",
                appointmentId,
                null,
                "{\"expiresAt\":\"" + expiresAt + "\"}",
                "console",
                null
        );
        return new AccessLinkView(managePath(appointmentId, raw), expiresAt);
    }

    private Offering requireOffering(UUID specialistId, UUID serviceId) {
        Offering offering = catalogService.offering(specialistId, serviceId)
                .orElseThrow(() -> DomainException.conflict("SERVICE_NOT_OFFERED", "This specialist does not offer the service"));
        if (!offering.serviceActive() || !offering.offered()) {
            throw DomainException.conflict("SERVICE_INACTIVE", "Service is not available");
        }
        return offering;
    }

    /**
     * The console view of a visit: the stored client's name, phone and trust, and for anyone but a master the owner's
     * "typed / stored" hint when the typed name differs. A MASTER reading a visit hidden from them (decision A) or a
     * cancelled one gets the typed name only, without the client id, stored name, phone or trust; the client is not
     * even looked up ({@link AppointmentView#clientWithheldFromMaster()}).
     */
    private AppointmentView enrich(AppointmentView row) {
        boolean master = isMaster();
        if (master && row.clientWithheldFromMaster()) {
            return maskedForMaster(row);
        }
        ClientView client = crmService.findById(row.clientId()).orElse(null);
        String trustLevel = null;
        try {
            trustLevel = retentionService.compute(row.clientId()).effectiveLevel();
        } catch (Exception ignored) {
            // Trust is best-effort on calendar/detail enrichment
        }
        if (client == null) {
            return trustLevel == null ? row : row.withTrustLevel(trustLevel);
        }
        String entered = master ? row.enteredName() : ownerHint(row.enteredName(), client.displayName());
        return withClientShown(row, entered, client.displayName(), client.normalizedPhone(), trustLevel);
    }

    private static final String HINT_SEPARATOR = " / ";
    static final String MASTER_PHONE_BOOKING_TASK = "MASTER_PHONE_BOOKING";

    /** A master's view of a visit that withholds its client: typed name only, no client id, phone or trust. */
    private static AppointmentView maskedForMaster(AppointmentView row) {
        String typed = typedOnly(row.enteredName());
        AppointmentView shown = withClientShown(row, typed, typed, null, null);
        return new AppointmentView(shown.id(), shown.specialistId(), shown.serviceId(), null, shown.status(),
                shown.serviceStart(), shown.serviceEnd(), shown.occupiedStart(), shown.occupiedEnd(),
                shown.serviceNameSnapshot(), shown.priceSnapshot(), shown.durationSnapshot(),
                shown.bufferBeforeSnapshot(), shown.bufferAfterSnapshot(), shown.currencyCode(), shown.discountAmount(),
                shown.amountReceived(), shown.enteredName(), shown.note(), shown.source(), shown.clientDisplayName(),
                null, null, shown.clientHiddenFromMaster());
    }

    /**
     * The typed name without anything a legacy row appended to it. Rows written before the visit stored the typed name
     * alone hold "typed / stored"; everything from the first {@code " / "} on is dropped, so no stored name (not even
     * one that contains " / " itself, nor one renamed or merged since) can show. A typed name that contains " / "
     * shows shortened: it fails closed.
     */
    static String typedOnly(String enteredName) {
        if (enteredName == null) {
            return null;
        }
        int separator = enteredName.indexOf(HINT_SEPARATOR);
        return separator < 0 ? enteredName : enteredName.substring(0, separator);
    }
    private static final int MAX_NAME_IN_TITLE = 150;

    /** The owner's "typed / stored" hint; rows written before the visit stored the typed name alone already carry it. */
    static String ownerHint(String typed, String stored) {
        if (typed == null || stored == null || typed.equals(stored) || typed.endsWith(HINT_SEPARATOR + stored)) {
            return typed;
        }
        return typed + HINT_SEPARATOR + stored;
    }

    /**
     * The view a public booking link gets (finding 4): visit facts and what the visitor typed, never the stored client
     * name, phone or trust; a legacy "typed / stored" row shows the typed part only ({@link #typedOnly}).
     */
    private static AppointmentView publicView(AppointmentView row) {
        String typed = typedOnly(row.enteredName());
        return withClientShown(row, typed, typed, null, null);
    }

    /** {@code row} with the typed name and the client fields of the response set as given. */
    private static AppointmentView withClientShown(AppointmentView row, String enteredName, String displayName,
                                                   String phone, String trustLevel) {
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
                enteredName,
                row.note(),
                row.source(),
                displayName,
                phone,
                trustLevel,
                row.clientHiddenFromMaster()
        );
    }

    private AppointmentView withStatus(AppointmentView current, AppointmentStatus status, String note, boolean hidden) {
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
                current.clientPhone(),
                current.trustLevel(),
                hidden
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

    private static boolean isMaster() {
        return ActorContext.current().map(ActorContext.Actor::isMaster).orElse(false);
    }

    private static boolean isOwner() {
        return ActorContext.current().map(ActorContext.Actor::isOwner).orElse(false);
    }

    /**
     * A MASTER books under the tenant's rules and the status machine, like the public flow: no notice, horizon and
     * schedule bypass, and a visit starts PENDING or CONFIRMED only (CONFIRMED is checked against the client's trust
     * once the client is known). Both switches stay with OWNER and SYSTEM (seed, imports).
     */
    private static void assertMasterKeepsBookingRules(CreateBookingCommand command) {
        if (!isMaster()) {
            return;
        }
        if (command.skipNoticeAndHorizon()) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Master cannot bypass the booking rules");
        }
        AppointmentStatus status = command.statusOverride();
        if (status != null && status != AppointmentStatus.PENDING && status != AppointmentStatus.CONFIRMED) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Master can create only pending or confirmed visits");
        }
    }

    /**
     * TD-17: no role settles a visit (COMPLETED or NO_SHOW) before it starts, neither by a transition nor by creating
     * it in that status; the demo seed and imports record only past visits as settled.
     */
    private void assertNotSettledBeforeStart(AppointmentStatus target, Instant serviceStart) {
        if ((target == AppointmentStatus.COMPLETED || target == AppointmentStatus.NO_SHOW)
                && serviceStart != null && clock.instant().isBefore(serviceStart)) {
            throw DomainException.conflict("VISIT_NOT_STARTED", "A visit can be completed or marked no-show only after it starts");
        }
    }

    /**
     * TD-19: a MASTER books an existing client by id only within their own scope (a non-cancelled visit with them), so
     * a booking cannot pull an unknown client of the tenant into it. Checked before any read or write.
     *
     * <p>Not locked on purpose (TD-25): a concurrent cancellation of the master's last visit with the client can land
     * between this check and the insert, but that outcome is the same as the legal order "book, then cancel" (the new
     * visit keeps the client in scope), so the race grants nothing the master could not get by acting a moment
     * earlier. A client-row lock here would not serialise anything, as cancellations lock the visit row only.
     */
    private void assertMasterMayBookClient(UUID clientId, UUID specialistId) {
        if (clientId != null && isMaster() && !appointments.hasActiveVisit(clientId, specialistId)) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Master can book only clients with a visit with them");
        }
    }

    private void assertMasterOwns(UUID specialistId) {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor != null && actor.isMaster() && !actor.specialistId().equals(specialistId)) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Master cannot book for another specialist");
        }
    }
}
