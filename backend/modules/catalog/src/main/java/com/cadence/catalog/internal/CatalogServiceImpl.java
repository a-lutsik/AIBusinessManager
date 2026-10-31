package com.cadence.catalog.internal;

import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.MasterServiceView;
import com.cadence.catalog.api.MatrixRowView;
import com.cadence.catalog.api.Offering;
import com.cadence.catalog.api.ScheduleExceptionView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.persistence.SqlStates;
import com.cadence.platform.tenancy.ActorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CatalogServiceImpl implements CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogServiceImpl.class);

    static final BookingRulesView DEFAULT_RULES = new BookingRulesView(
            15, 60, 28, true, 2, false, false, 1, true
    );

    private static final String KEYCLOAK_LINK_INDEX = "specialist_keycloak_user_uq";

    private final CatalogRepository repository;
    private final AuditLogger auditLogger;

    public CatalogServiceImpl(CatalogRepository repository, AuditLogger auditLogger) {
        this.repository = repository;
        this.auditLogger = auditLogger;
    }

    @Override
    public String moduleName() {
        return "catalog";
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceView> listServices(boolean publicOnly) {
        return repository.listServices(publicOnly);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ServiceView> findService(UUID serviceId) {
        return repository.findService(serviceId);
    }

    @Override
    @Transactional
    public ServiceView upsertService(ServiceView service) {
        checked(() -> CatalogValidation.service(service));
        UUID id = service.id() == null ? UUID.randomUUID() : service.id();
        ServiceView stored = new ServiceView(
                id,
                service.name(),
                service.description(),
                service.durationMinutes(),
                service.priceMinor(),
                service.bufferBeforeMinutes(),
                service.bufferAfterMinutes(),
                service.color(),
                service.active(),
                service.publicVisible()
        );
        Optional<ServiceView> before = repository.findService(id);
        repository.upsertService(stored);
        audit(before.isPresent() ? "service.update" : "service.create", "service", id,
                before.map(CatalogServiceImpl::state).orElse(null), state(stored));
        log.info("Service saved: id={}, durationMinutes={}, active={}, publicVisible={}",
                id, stored.durationMinutes(), stored.active(), stored.publicVisible());
        return stored;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpecialistView> listSpecialists(boolean activeOnly) {
        return repository.listSpecialists(activeOnly);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SpecialistView> findSpecialist(UUID specialistId) {
        return repository.findSpecialist(specialistId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SpecialistView> findSpecialistByKeycloakUserId(String keycloakUserId) {
        return repository.findSpecialistByKeycloakUserId(keycloakUserId);
    }

    @Override
    @Transactional
    public SpecialistView upsertSpecialist(SpecialistView specialist) {
        checked(() -> CatalogValidation.specialist(specialist));
        UUID id = specialist.id() == null ? UUID.randomUUID() : specialist.id();
        // Two specialists on one sign-in would make that sign-in unresolvable (findSpecialistByKeycloakUserId).
        if (specialist.keycloakUserId() != null && repository.findSpecialistByKeycloakUserId(specialist.keycloakUserId())
                .filter(linked -> !linked.id().equals(id)).isPresent()) {
            throw alreadyLinked();
        }
        SpecialistView stored = new SpecialistView(
                id,
                specialist.displayName(),
                specialist.keycloakUserId(),
                specialist.calendarColor(),
                specialist.active()
        );
        Optional<SpecialistView> before = repository.findSpecialist(id);
        try {
            repository.upsertSpecialist(stored);
        } catch (RuntimeException e) {
            if (!SqlStates.isUniqueViolation(e, KEYCLOAK_LINK_INDEX)) {
                throw e;
            }
            log.warn("Sign-in link lost a race: specialistId={}", id);
            throw alreadyLinked();
        }
        // Read back: a save without keycloakUserId keeps the stored link, and the answer has to show it.
        SpecialistView saved = repository.findSpecialist(id).orElse(stored);
        audit(before.isPresent() ? "specialist.update" : "specialist.create", "specialist", id,
                before.map(CatalogServiceImpl::state).orElse(null), state(saved));
        log.info("Specialist saved: id={}, active={}, linked={}", id, saved.active(), saved.keycloakUserId() != null);
        return saved;
    }

    @Override
    @Transactional
    public void removeKeycloakLink(UUID specialistId) {
        requireSpecialist(specialistId);
        boolean removed = repository.removeKeycloakLink(specialistId) > 0;
        if (removed) {
            audit("specialist.keycloak_link.remove", "specialist", specialistId, "{\"linked\":true}", "{\"linked\":false}");
        }
        log.info("Sign-in link removal: specialistId={}, removed={}", specialistId, removed);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MasterServiceView> listMatrix(UUID specialistId) {
        return repository.listMatrix(specialistId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MatrixRowView> listMatrixRows(UUID specialistId) {
        List<ServiceView> services = repository.listServices(false);
        Map<UUID, MasterServiceView> byService = repository.listMatrix(specialistId).stream()
                .collect(Collectors.toMap(MasterServiceView::serviceId, Function.identity(), (a, b) -> a));
        List<MatrixRowView> rows = new ArrayList<>();
        for (ServiceView service : services) {
            MasterServiceView matrix = byService.get(service.id());
            rows.add(new MatrixRowView(
                    matrix == null ? null : matrix.id(),
                    specialistId,
                    service.id(),
                    service.name(),
                    service.color(),
                    matrix != null && matrix.offered(),
                    service.durationMinutes(),
                    service.priceMinor(),
                    service.bufferBeforeMinutes(),
                    service.bufferAfterMinutes(),
                    matrix == null ? null : matrix.durationMinutesOverride(),
                    matrix == null ? null : matrix.priceMinorOverride(),
                    matrix == null ? null : matrix.bufferBeforeMinutesOverride(),
                    matrix == null ? null : matrix.bufferAfterMinutesOverride()
            ));
        }
        return rows;
    }

    @Override
    @Transactional
    public MasterServiceView upsertMatrix(MasterServiceView row) {
        checked(() -> CatalogValidation.matrixRow(row));
        requireSpecialist(row.specialistId());
        if (repository.findService(row.serviceId()).isEmpty()) {
            throw DomainException.notFound("SERVICE_NOT_FOUND", "Service not found");
        }
        // One row per specialist and service: an existing row keeps its id, whatever the caller sent.
        Optional<MasterServiceView> before = repository.listMatrix(row.specialistId()).stream()
                .filter(existing -> existing.serviceId().equals(row.serviceId()))
                .findFirst();
        UUID id = before.map(MasterServiceView::id).orElse(row.id() == null ? UUID.randomUUID() : row.id());
        MasterServiceView stored = new MasterServiceView(
                id,
                row.specialistId(),
                row.serviceId(),
                row.offered(),
                row.durationMinutesOverride(),
                row.priceMinorOverride(),
                row.bufferBeforeMinutesOverride(),
                row.bufferAfterMinutesOverride()
        );
        repository.upsertMatrix(stored);
        audit("master_service.save", "master_service", id, before.map(CatalogServiceImpl::state).orElse(null), state(stored));
        log.info("Matrix row saved: specialistId={}, serviceId={}, offered={}", row.specialistId(), row.serviceId(), row.offered());
        return stored;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Offering> offering(UUID specialistId, UUID serviceId) {
        return repository.offering(specialistId, serviceId).filter(Offering::offered);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Offering> offeringsForService(UUID serviceId) {
        return repository.offeringsForService(serviceId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WeeklyInterval> weeklySchedule(UUID specialistId) {
        return repository.weeklySchedule(specialistId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WeeklyInterval> allWeeklySchedules() {
        return repository.allWeeklySchedules();
    }

    @Override
    @Transactional
    public void replaceWeeklySchedule(UUID specialistId, List<WeeklyInterval> intervals) {
        checked(() -> CatalogValidation.weeklySchedule(intervals));
        // The row lock of the specialist: two replacements must not end up with the intervals of both.
        if (!repository.lockSpecialist(specialistId)) {
            throw specialistNotFound();
        }
        String before = state(repository.weeklySchedule(specialistId));
        repository.replaceWeeklySchedule(specialistId, intervals);
        audit("schedule.replace", "specialist", specialistId, before, state(intervals));
        log.info("Weekly schedule replaced: specialistId={}, intervals={}", specialistId, intervals.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScheduleExceptionView> exceptions(UUID specialistId, LocalDate from, LocalDate to) {
        return repository.exceptions(specialistId, from, to);
    }

    @Override
    @Transactional
    public ScheduleExceptionView upsertException(ScheduleExceptionView exception) {
        checked(() -> CatalogValidation.exception(exception));
        requireSpecialist(exception.specialistId());
        Optional<ScheduleExceptionView> before = exception.id() == null
                ? Optional.empty()
                : repository.findException(exception.specialistId(), exception.id());
        if (exception.id() != null && before.isEmpty()) {
            throw exceptionNotFound();
        }
        UUID id = exception.id() == null ? UUID.randomUUID() : exception.id();
        ScheduleExceptionView stored = new ScheduleExceptionView(
                id,
                exception.specialistId(),
                exception.date(),
                exception.kind(),
                exception.startMinute(),
                exception.endMinute(),
                exception.note()
        );
        repository.upsertException(stored);
        audit("schedule_exception.save", "schedule_exception", id, before.map(CatalogServiceImpl::state).orElse(null), state(stored));
        log.info("Schedule exception saved: id={}, specialistId={}, date={}, kind={}", id, stored.specialistId(), stored.date(), stored.kind());
        return stored;
    }

    @Override
    @Transactional
    public void deleteException(UUID specialistId, UUID exceptionId) {
        requireSpecialist(specialistId);
        ScheduleExceptionView before = repository.findException(specialistId, exceptionId).orElseThrow(CatalogServiceImpl::exceptionNotFound);
        repository.deleteException(specialistId, exceptionId);
        audit("schedule_exception.delete", "schedule_exception", exceptionId, state(before), null);
        log.info("Schedule exception deleted: id={}, specialistId={}", exceptionId, specialistId);
    }

    @Override
    @Transactional(readOnly = true)
    public BookingRulesView rules() {
        return repository.rules().orElse(DEFAULT_RULES);
    }

    @Override
    @Transactional
    public BookingRulesView saveRules(BookingRulesView rules) {
        if (rules.slotStepMinutes() <= 0 || rules.horizonDays() <= 0) {
            throw DomainException.badRequest("INVALID_RULES", "Slot step and horizon must be positive");
        }
        repository.saveRules(rules);
        return rules;
    }

    /** The foreign keys see neither the tenant nor RLS: a write under another tenant's specialist must stop here. */
    private void requireSpecialist(UUID specialistId) {
        if (repository.findSpecialist(specialistId).isEmpty()) {
            throw specialistNotFound();
        }
    }

    private static DomainException specialistNotFound() {
        return DomainException.notFound("SPECIALIST_NOT_FOUND", "Specialist not found");
    }

    private static DomainException exceptionNotFound() {
        return DomainException.notFound("EXCEPTION_NOT_FOUND", "Schedule exception not found");
    }

    private static DomainException alreadyLinked() {
        return DomainException.conflict("KEYCLOAK_USER_ALREADY_LINKED", "This sign-in is linked to another specialist");
    }

    /** In the transaction of the change. A seed or a job has no owner behind it: the actor is the system. */
    private void audit(String action, String entityType, UUID entityId, String before, String after) {
        auditLogger.record(
                ActorContext.current().map(ActorContext.Actor::actorId).orElse("system"),
                ActorContext.current().map(actor -> actor.role().name()).orElse("SYSTEM"),
                action,
                entityType,
                entityId,
                before,
                after,
                "catalog",
                null
        );
    }

    // Audit states hold numbers and flags: free text (names, descriptions, notes) and the sign-in id stay out, they can
    // hold personal data.

    private static String state(ServiceView s) {
        return "{\"durationMinutes\":%d,\"priceMinor\":%d,\"bufferBeforeMinutes\":%d,\"bufferAfterMinutes\":%d,\"color\":\"%s\",\"active\":%b,\"publicVisible\":%b}"
                .formatted(s.durationMinutes(), s.priceMinor(), s.bufferBeforeMinutes(), s.bufferAfterMinutes(), s.color(), s.active(), s.publicVisible());
    }

    private static String state(SpecialistView s) {
        return "{\"calendarColor\":\"%s\",\"active\":%b,\"linked\":%b}".formatted(s.calendarColor(), s.active(), s.keycloakUserId() != null);
    }

    private static String state(MasterServiceView m) {
        return "{\"specialistId\":\"%s\",\"serviceId\":\"%s\",\"offered\":%b,\"durationMinutesOverride\":%s,\"priceMinorOverride\":%s,\"bufferBeforeMinutesOverride\":%s,\"bufferAfterMinutesOverride\":%s}"
                .formatted(m.specialistId(), m.serviceId(), m.offered(), m.durationMinutesOverride(), m.priceMinorOverride(),
                        m.bufferBeforeMinutesOverride(), m.bufferAfterMinutesOverride());
    }

    private static String state(List<WeeklyInterval> intervals) {
        String compact = intervals.stream()
                .map(i -> i.weekday() + ":" + i.startMinute() + "-" + i.endMinute())
                .collect(Collectors.joining(","));
        return "{\"intervals\":\"" + compact + "\"}";
    }

    private static String state(ScheduleExceptionView e) {
        return "{\"specialistId\":\"%s\",\"date\":\"%s\",\"kind\":\"%s\",\"startMinute\":%s,\"endMinute\":%s}"
                .formatted(e.specialistId(), e.date(), e.kind(), e.startMinute(), e.endMinute());
    }

    /** Runs a validation; a refusal is logged with its code only, the input can hold names. */
    private static void checked(Runnable validation) {
        try {
            validation.run();
        } catch (DomainException refused) {
            log.warn("Catalog input refused: code={}", refused.code());
            throw refused;
        }
    }
}
