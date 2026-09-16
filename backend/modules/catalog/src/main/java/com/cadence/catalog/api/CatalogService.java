package com.cadence.catalog.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public catalog port — services, masters, schedules, booking rules. */
public interface CatalogService {

    String moduleName();

    List<ServiceView> listServices(boolean publicOnly);

    Optional<ServiceView> findService(UUID serviceId);

    ServiceView upsertService(ServiceView service);

    List<SpecialistView> listSpecialists(boolean activeOnly);

    Optional<SpecialistView> findSpecialist(UUID specialistId);

    Optional<SpecialistView> findSpecialistByKeycloakUserId(String keycloakUserId);

    SpecialistView upsertSpecialist(SpecialistView specialist);

    List<MasterServiceView> listMatrix(UUID specialistId);

    /** All catalog services joined with matrix overrides for a specialist. */
    List<MatrixRowView> listMatrixRows(UUID specialistId);

    MasterServiceView upsertMatrix(MasterServiceView row);

    Optional<Offering> offering(UUID specialistId, UUID serviceId);

    List<Offering> offeringsForService(UUID serviceId);

    List<WeeklyInterval> weeklySchedule(UUID specialistId);

    List<WeeklyInterval> allWeeklySchedules();

    void replaceWeeklySchedule(UUID specialistId, List<WeeklyInterval> intervals);

    List<ScheduleExceptionView> exceptions(UUID specialistId, LocalDate from, LocalDate to);

    ScheduleExceptionView upsertException(ScheduleExceptionView exception);

    BookingRulesView rules();

    BookingRulesView saveRules(BookingRulesView rules);
}
