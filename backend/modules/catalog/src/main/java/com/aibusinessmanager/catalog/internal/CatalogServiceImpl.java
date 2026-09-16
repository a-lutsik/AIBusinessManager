package com.aibusinessmanager.catalog.internal;

import com.aibusinessmanager.catalog.api.BookingRulesView;
import com.aibusinessmanager.catalog.api.CatalogService;
import com.aibusinessmanager.catalog.api.MasterServiceView;
import com.aibusinessmanager.catalog.api.MatrixRowView;
import com.aibusinessmanager.catalog.api.Offering;
import com.aibusinessmanager.catalog.api.ScheduleExceptionView;
import com.aibusinessmanager.catalog.api.ServiceView;
import com.aibusinessmanager.catalog.api.SpecialistView;
import com.aibusinessmanager.catalog.api.WeeklyInterval;
import com.aibusinessmanager.platform.error.DomainException;
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

    static final BookingRulesView DEFAULT_RULES = new BookingRulesView(
            15, 60, 28, true, 2, false, false, 1, true
    );

    private final CatalogRepository repository;

    public CatalogServiceImpl(CatalogRepository repository) {
        this.repository = repository;
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
        repository.upsertService(stored);
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
        UUID id = specialist.id() == null ? UUID.randomUUID() : specialist.id();
        SpecialistView stored = new SpecialistView(
                id,
                specialist.displayName(),
                specialist.keycloakUserId(),
                specialist.calendarColor(),
                specialist.active()
        );
        repository.upsertSpecialist(stored);
        return stored;
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
        UUID id = row.id() == null ? UUID.randomUUID() : row.id();
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
        repository.replaceWeeklySchedule(specialistId, intervals);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScheduleExceptionView> exceptions(UUID specialistId, LocalDate from, LocalDate to) {
        return repository.exceptions(specialistId, from, to);
    }

    @Override
    @Transactional
    public ScheduleExceptionView upsertException(ScheduleExceptionView exception) {
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
        return stored;
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
}
