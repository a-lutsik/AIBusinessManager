package com.cadence.catalog.internal;

import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.MasterServiceView;
import com.cadence.catalog.api.Offering;
import com.cadence.catalog.api.ScheduleExceptionView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.BOOKING_RULES;
import static com.cadence.platform.jooq.Tables.CATALOG_SERVICE;
import static com.cadence.platform.jooq.Tables.MASTER_SERVICE;
import static com.cadence.platform.jooq.Tables.SCHEDULE_EXCEPTION;
import static com.cadence.platform.jooq.Tables.SPECIALIST;
import static com.cadence.platform.jooq.Tables.WEEKLY_SCHEDULE;

@Repository
class CatalogRepository {

    private final DSLContext dsl;

    CatalogRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    List<ServiceView> listServices(boolean publicOnly) {
        var condition = TenantAwareDsl.tenantEquals(CATALOG_SERVICE.TENANT_ID);
        if (publicOnly) {
            condition = condition.and(CATALOG_SERVICE.PUBLIC_VISIBLE.isTrue()).and(CATALOG_SERVICE.ACTIVE.isTrue());
        }
        return dsl.selectFrom(CATALOG_SERVICE).where(condition).orderBy(CATALOG_SERVICE.NAME.asc()).fetch(this::toService);
    }

    Optional<ServiceView> findService(UUID id) {
        return dsl.selectFrom(CATALOG_SERVICE)
                .where(TenantAwareDsl.tenantEquals(CATALOG_SERVICE.TENANT_ID).and(CATALOG_SERVICE.ID.eq(id)))
                .fetchOptional(this::toService);
    }

    void upsertService(ServiceView service) {
        UUID tenantId = TenantContext.require();
        dsl.insertInto(CATALOG_SERVICE)
                .set(CATALOG_SERVICE.ID, service.id())
                .set(CATALOG_SERVICE.TENANT_ID, tenantId)
                .set(CATALOG_SERVICE.NAME, service.name())
                .set(CATALOG_SERVICE.DESCRIPTION, service.description())
                .set(CATALOG_SERVICE.DURATION_MINUTES, service.durationMinutes())
                .set(CATALOG_SERVICE.PRICE_MINOR, service.priceMinor())
                .set(CATALOG_SERVICE.BUFFER_BEFORE_MINUTES, service.bufferBeforeMinutes())
                .set(CATALOG_SERVICE.BUFFER_AFTER_MINUTES, service.bufferAfterMinutes())
                .set(CATALOG_SERVICE.COLOR, service.color())
                .set(CATALOG_SERVICE.ACTIVE, service.active())
                .set(CATALOG_SERVICE.PUBLIC_VISIBLE, service.publicVisible())
                .set(CATALOG_SERVICE.CREATED_AT, Utc.toLocal(Instant.now()))
                .onConflict(CATALOG_SERVICE.ID)
                .doUpdate()
                .set(CATALOG_SERVICE.NAME, service.name())
                .set(CATALOG_SERVICE.DESCRIPTION, service.description())
                .set(CATALOG_SERVICE.DURATION_MINUTES, service.durationMinutes())
                .set(CATALOG_SERVICE.PRICE_MINOR, service.priceMinor())
                .set(CATALOG_SERVICE.BUFFER_BEFORE_MINUTES, service.bufferBeforeMinutes())
                .set(CATALOG_SERVICE.BUFFER_AFTER_MINUTES, service.bufferAfterMinutes())
                .set(CATALOG_SERVICE.COLOR, service.color())
                .set(CATALOG_SERVICE.ACTIVE, service.active())
                .set(CATALOG_SERVICE.PUBLIC_VISIBLE, service.publicVisible())
                .execute();
    }

    List<SpecialistView> listSpecialists(boolean activeOnly) {
        var condition = TenantAwareDsl.tenantEquals(SPECIALIST.TENANT_ID);
        if (activeOnly) {
            condition = condition.and(SPECIALIST.ACTIVE.isTrue());
        }
        return dsl.selectFrom(SPECIALIST).where(condition).orderBy(SPECIALIST.DISPLAY_NAME.asc()).fetch(this::toSpecialist);
    }

    Optional<SpecialistView> findSpecialist(UUID id) {
        return dsl.selectFrom(SPECIALIST)
                .where(TenantAwareDsl.tenantEquals(SPECIALIST.TENANT_ID).and(SPECIALIST.ID.eq(id)))
                .fetchOptional(this::toSpecialist);
    }

    Optional<SpecialistView> findSpecialistByKeycloakUserId(String keycloakUserId) {
        return dsl.selectFrom(SPECIALIST)
                .where(TenantAwareDsl.tenantEquals(SPECIALIST.TENANT_ID).and(SPECIALIST.KEYCLOAK_USER_ID.eq(keycloakUserId)))
                .fetchOptional(this::toSpecialist);
    }

    void upsertSpecialist(SpecialistView specialist) {
        UUID tenantId = TenantContext.require();
        dsl.insertInto(SPECIALIST)
                .set(SPECIALIST.ID, specialist.id())
                .set(SPECIALIST.TENANT_ID, tenantId)
                .set(SPECIALIST.DISPLAY_NAME, specialist.displayName())
                .set(SPECIALIST.KEYCLOAK_USER_ID, specialist.keycloakUserId())
                .set(SPECIALIST.CALENDAR_COLOR, specialist.calendarColor())
                .set(SPECIALIST.ACTIVE, specialist.active())
                .set(SPECIALIST.CREATED_AT, Utc.toLocal(Instant.now()))
                .onConflict(SPECIALIST.ID)
                .doUpdate()
                .set(SPECIALIST.DISPLAY_NAME, specialist.displayName())
                // Null keeps the link: only the operator links a sign-in, and an edit form must not erase it.
                .set(SPECIALIST.KEYCLOAK_USER_ID, DSL.coalesce(DSL.val(specialist.keycloakUserId(), SPECIALIST.KEYCLOAK_USER_ID), SPECIALIST.KEYCLOAK_USER_ID))
                .set(SPECIALIST.CALENDAR_COLOR, specialist.calendarColor())
                .set(SPECIALIST.ACTIVE, specialist.active())
                .execute();
    }

    int removeKeycloakLink(UUID specialistId) {
        return dsl.update(SPECIALIST)
                .setNull(SPECIALIST.KEYCLOAK_USER_ID)
                .where(TenantAwareDsl.tenantEquals(SPECIALIST.TENANT_ID)
                        .and(SPECIALIST.ID.eq(specialistId))
                        .and(SPECIALIST.KEYCLOAK_USER_ID.isNotNull()))
                .execute();
    }

    /** Takes the row lock of the specialist; false when there is no such specialist in this tenant. */
    boolean lockSpecialist(UUID specialistId) {
        return dsl.select(SPECIALIST.ID).from(SPECIALIST)
                .where(TenantAwareDsl.tenantEquals(SPECIALIST.TENANT_ID).and(SPECIALIST.ID.eq(specialistId)))
                .forUpdate()
                .fetchOptional()
                .isPresent();
    }

    List<MasterServiceView> listMatrix(UUID specialistId) {
        var condition = TenantAwareDsl.tenantEquals(MASTER_SERVICE.TENANT_ID);
        if (specialistId != null) {
            condition = condition.and(MASTER_SERVICE.SPECIALIST_ID.eq(specialistId));
        }
        return dsl.selectFrom(MASTER_SERVICE).where(condition).fetch(this::toMatrix);
    }

    void upsertMatrix(MasterServiceView row) {
        UUID tenantId = TenantContext.require();
        dsl.insertInto(MASTER_SERVICE)
                .set(MASTER_SERVICE.ID, row.id())
                .set(MASTER_SERVICE.TENANT_ID, tenantId)
                .set(MASTER_SERVICE.SPECIALIST_ID, row.specialistId())
                .set(MASTER_SERVICE.SERVICE_ID, row.serviceId())
                .set(MASTER_SERVICE.OFFERED, row.offered())
                .set(MASTER_SERVICE.DURATION_MINUTES_OVERRIDE, row.durationMinutesOverride())
                .set(MASTER_SERVICE.PRICE_MINOR_OVERRIDE, row.priceMinorOverride())
                .set(MASTER_SERVICE.BUFFER_BEFORE_MINUTES_OVERRIDE, row.bufferBeforeMinutesOverride())
                .set(MASTER_SERVICE.BUFFER_AFTER_MINUTES_OVERRIDE, row.bufferAfterMinutesOverride())
                .onConflict(MASTER_SERVICE.TENANT_ID, MASTER_SERVICE.SPECIALIST_ID, MASTER_SERVICE.SERVICE_ID)
                .doUpdate()
                .set(MASTER_SERVICE.OFFERED, row.offered())
                .set(MASTER_SERVICE.DURATION_MINUTES_OVERRIDE, row.durationMinutesOverride())
                .set(MASTER_SERVICE.PRICE_MINOR_OVERRIDE, row.priceMinorOverride())
                .set(MASTER_SERVICE.BUFFER_BEFORE_MINUTES_OVERRIDE, row.bufferBeforeMinutesOverride())
                .set(MASTER_SERVICE.BUFFER_AFTER_MINUTES_OVERRIDE, row.bufferAfterMinutesOverride())
                .execute();
    }

    Optional<Offering> offering(UUID specialistId, UUID serviceId) {
        Optional<MasterServiceView> matrix = dsl.selectFrom(MASTER_SERVICE)
                .where(TenantAwareDsl.tenantEquals(MASTER_SERVICE.TENANT_ID)
                        .and(MASTER_SERVICE.SPECIALIST_ID.eq(specialistId))
                        .and(MASTER_SERVICE.SERVICE_ID.eq(serviceId)))
                .fetchOptional(this::toMatrix);
        Optional<ServiceView> service = findService(serviceId);
        boolean specialistActive = dsl.fetchExists(SPECIALIST, TenantAwareDsl.tenantEquals(SPECIALIST.TENANT_ID)
                .and(SPECIALIST.ID.eq(specialistId))
                .and(SPECIALIST.ACTIVE.isTrue()));
        if (matrix.isEmpty() || service.isEmpty() || !specialistActive) {
            return Optional.empty();
        }
        return Optional.of(toOffering(matrix.get(), service.get()));
    }

    List<Offering> offeringsForService(UUID serviceId) {
        Optional<ServiceView> service = findService(serviceId);
        if (service.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(MASTER_SERVICE)
                .where(TenantAwareDsl.tenantEquals(MASTER_SERVICE.TENANT_ID)
                        .and(MASTER_SERVICE.SERVICE_ID.eq(serviceId))
                        .and(MASTER_SERVICE.OFFERED.isTrue())
                        .and(MASTER_SERVICE.SPECIALIST_ID.in(dsl.select(SPECIALIST.ID).from(SPECIALIST)
                                .where(TenantAwareDsl.tenantEquals(SPECIALIST.TENANT_ID).and(SPECIALIST.ACTIVE.isTrue())))))
                .fetch(this::toMatrix)
                .stream()
                .filter(MasterServiceView::offered)
                .map(row -> toOffering(row, service.get()))
                .filter(o -> o.serviceActive())
                .toList();
    }

    List<WeeklyInterval> weeklySchedule(UUID specialistId) {
        return dsl.selectFrom(WEEKLY_SCHEDULE)
                .where(TenantAwareDsl.tenantEquals(WEEKLY_SCHEDULE.TENANT_ID).and(WEEKLY_SCHEDULE.SPECIALIST_ID.eq(specialistId)))
                .orderBy(WEEKLY_SCHEDULE.WEEKDAY.asc(), WEEKLY_SCHEDULE.START_MINUTE.asc())
                .fetch(r -> new WeeklyInterval(
                        r.get(WEEKLY_SCHEDULE.ID),
                        r.get(WEEKLY_SCHEDULE.SPECIALIST_ID),
                        r.get(WEEKLY_SCHEDULE.WEEKDAY),
                        r.get(WEEKLY_SCHEDULE.START_MINUTE),
                        r.get(WEEKLY_SCHEDULE.END_MINUTE)
                ));
    }

    List<WeeklyInterval> allWeeklySchedules() {
        return dsl.selectFrom(WEEKLY_SCHEDULE)
                .where(TenantAwareDsl.tenantEquals(WEEKLY_SCHEDULE.TENANT_ID))
                .orderBy(WEEKLY_SCHEDULE.SPECIALIST_ID.asc(), WEEKLY_SCHEDULE.WEEKDAY.asc(), WEEKLY_SCHEDULE.START_MINUTE.asc())
                .fetch(r -> new WeeklyInterval(
                        r.get(WEEKLY_SCHEDULE.ID),
                        r.get(WEEKLY_SCHEDULE.SPECIALIST_ID),
                        r.get(WEEKLY_SCHEDULE.WEEKDAY),
                        r.get(WEEKLY_SCHEDULE.START_MINUTE),
                        r.get(WEEKLY_SCHEDULE.END_MINUTE)
                ));
    }

    void replaceWeeklySchedule(UUID specialistId, List<WeeklyInterval> intervals) {
        UUID tenantId = TenantContext.require();
        dsl.deleteFrom(WEEKLY_SCHEDULE)
                .where(TenantAwareDsl.tenantEquals(WEEKLY_SCHEDULE.TENANT_ID).and(WEEKLY_SCHEDULE.SPECIALIST_ID.eq(specialistId)))
                .execute();
        for (WeeklyInterval interval : intervals) {
            dsl.insertInto(WEEKLY_SCHEDULE)
                    // Always a new id: one sent by the caller could only run into a row it does not own.
                    .set(WEEKLY_SCHEDULE.ID, UUID.randomUUID())
                    .set(WEEKLY_SCHEDULE.TENANT_ID, tenantId)
                    .set(WEEKLY_SCHEDULE.SPECIALIST_ID, specialistId)
                    .set(WEEKLY_SCHEDULE.WEEKDAY, interval.weekday())
                    .set(WEEKLY_SCHEDULE.START_MINUTE, interval.startMinute())
                    .set(WEEKLY_SCHEDULE.END_MINUTE, interval.endMinute())
                    .execute();
        }
    }

    List<ScheduleExceptionView> exceptions(UUID specialistId, LocalDate from, LocalDate to) {
        var condition = TenantAwareDsl.tenantEquals(SCHEDULE_EXCEPTION.TENANT_ID)
                .and(SCHEDULE_EXCEPTION.SPECIALIST_ID.eq(specialistId));
        if (from != null) {
            condition = condition.and(SCHEDULE_EXCEPTION.EXCEPTION_DATE.ge(from));
        }
        if (to != null) {
            condition = condition.and(SCHEDULE_EXCEPTION.EXCEPTION_DATE.le(to));
        }
        // A stable listing; which exception of a date applies does not depend on it (WorkingHours.appliedException).
        return dsl.selectFrom(SCHEDULE_EXCEPTION).where(condition)
                .orderBy(SCHEDULE_EXCEPTION.EXCEPTION_DATE, SCHEDULE_EXCEPTION.ID)
                .fetch(r -> new ScheduleExceptionView(
                r.get(SCHEDULE_EXCEPTION.ID),
                r.get(SCHEDULE_EXCEPTION.SPECIALIST_ID),
                r.get(SCHEDULE_EXCEPTION.EXCEPTION_DATE),
                r.get(SCHEDULE_EXCEPTION.KIND),
                r.get(SCHEDULE_EXCEPTION.START_MINUTE),
                r.get(SCHEDULE_EXCEPTION.END_MINUTE),
                r.get(SCHEDULE_EXCEPTION.NOTE)
        ));
    }

    void upsertException(ScheduleExceptionView exception) {
        UUID tenantId = TenantContext.require();
        dsl.insertInto(SCHEDULE_EXCEPTION)
                .set(SCHEDULE_EXCEPTION.ID, exception.id())
                .set(SCHEDULE_EXCEPTION.TENANT_ID, tenantId)
                .set(SCHEDULE_EXCEPTION.SPECIALIST_ID, exception.specialistId())
                .set(SCHEDULE_EXCEPTION.EXCEPTION_DATE, exception.date())
                .set(SCHEDULE_EXCEPTION.KIND, exception.kind())
                .set(SCHEDULE_EXCEPTION.START_MINUTE, exception.startMinute())
                .set(SCHEDULE_EXCEPTION.END_MINUTE, exception.endMinute())
                .set(SCHEDULE_EXCEPTION.NOTE, exception.note())
                .onConflict(SCHEDULE_EXCEPTION.ID)
                .doUpdate()
                .set(SCHEDULE_EXCEPTION.EXCEPTION_DATE, exception.date())
                .set(SCHEDULE_EXCEPTION.KIND, exception.kind())
                .set(SCHEDULE_EXCEPTION.START_MINUTE, exception.startMinute())
                .set(SCHEDULE_EXCEPTION.END_MINUTE, exception.endMinute())
                .set(SCHEDULE_EXCEPTION.NOTE, exception.note())
                .execute();
    }

    Optional<ScheduleExceptionView> findException(UUID specialistId, UUID id) {
        return dsl.selectFrom(SCHEDULE_EXCEPTION).where(exceptionOf(specialistId, id)).fetchOptional(r -> new ScheduleExceptionView(
                r.get(SCHEDULE_EXCEPTION.ID),
                r.get(SCHEDULE_EXCEPTION.SPECIALIST_ID),
                r.get(SCHEDULE_EXCEPTION.EXCEPTION_DATE),
                r.get(SCHEDULE_EXCEPTION.KIND),
                r.get(SCHEDULE_EXCEPTION.START_MINUTE),
                r.get(SCHEDULE_EXCEPTION.END_MINUTE),
                r.get(SCHEDULE_EXCEPTION.NOTE)
        ));
    }

    int deleteException(UUID specialistId, UUID id) {
        return dsl.deleteFrom(SCHEDULE_EXCEPTION).where(exceptionOf(specialistId, id)).execute();
    }

    private static Condition exceptionOf(UUID specialistId, UUID id) {
        return TenantAwareDsl.tenantEquals(SCHEDULE_EXCEPTION.TENANT_ID)
                .and(SCHEDULE_EXCEPTION.SPECIALIST_ID.eq(specialistId))
                .and(SCHEDULE_EXCEPTION.ID.eq(id));
    }

    Optional<BookingRulesView> rules() {
        return dsl.selectFrom(BOOKING_RULES)
                .where(TenantAwareDsl.tenantEquals(BOOKING_RULES.TENANT_ID))
                .fetchOptional(r -> new BookingRulesView(
                        r.get(BOOKING_RULES.SLOT_STEP_MINUTES),
                        r.get(BOOKING_RULES.MIN_NOTICE_MINUTES),
                        r.get(BOOKING_RULES.HORIZON_DAYS),
                        r.get(BOOKING_RULES.CLIENT_RESCHEDULE_ALLOWED),
                        r.get(BOOKING_RULES.LATE_CANCELLATION_HOURS),
                        r.get(BOOKING_RULES.NEW_CLIENT_REQUIRES_CONFIRMATION),
                        r.get(BOOKING_RULES.DEDUCT_PACKAGE_ON_NO_SHOW),
                        r.get(BOOKING_RULES.WEEK_STARTS_ON),
                        r.get(BOOKING_RULES.TIME_FORMAT_24H)
                ));
    }

    void saveRules(BookingRulesView rules) {
        UUID tenantId = TenantContext.require();
        dsl.insertInto(BOOKING_RULES)
                .set(BOOKING_RULES.TENANT_ID, tenantId)
                .set(BOOKING_RULES.SLOT_STEP_MINUTES, rules.slotStepMinutes())
                .set(BOOKING_RULES.MIN_NOTICE_MINUTES, rules.minNoticeMinutes())
                .set(BOOKING_RULES.HORIZON_DAYS, rules.horizonDays())
                .set(BOOKING_RULES.CLIENT_RESCHEDULE_ALLOWED, rules.clientRescheduleAllowed())
                .set(BOOKING_RULES.LATE_CANCELLATION_HOURS, rules.lateCancellationHours())
                .set(BOOKING_RULES.NEW_CLIENT_REQUIRES_CONFIRMATION, rules.newClientRequiresConfirmation())
                .set(BOOKING_RULES.DEDUCT_PACKAGE_ON_NO_SHOW, rules.deductPackageOnNoShow())
                .set(BOOKING_RULES.WEEK_STARTS_ON, rules.weekStartsOn())
                .set(BOOKING_RULES.TIME_FORMAT_24H, rules.timeFormat24h())
                .onConflict(BOOKING_RULES.TENANT_ID)
                .doUpdate()
                .set(BOOKING_RULES.SLOT_STEP_MINUTES, rules.slotStepMinutes())
                .set(BOOKING_RULES.MIN_NOTICE_MINUTES, rules.minNoticeMinutes())
                .set(BOOKING_RULES.HORIZON_DAYS, rules.horizonDays())
                .set(BOOKING_RULES.CLIENT_RESCHEDULE_ALLOWED, rules.clientRescheduleAllowed())
                .set(BOOKING_RULES.LATE_CANCELLATION_HOURS, rules.lateCancellationHours())
                .set(BOOKING_RULES.NEW_CLIENT_REQUIRES_CONFIRMATION, rules.newClientRequiresConfirmation())
                .set(BOOKING_RULES.DEDUCT_PACKAGE_ON_NO_SHOW, rules.deductPackageOnNoShow())
                .set(BOOKING_RULES.WEEK_STARTS_ON, rules.weekStartsOn())
                .set(BOOKING_RULES.TIME_FORMAT_24H, rules.timeFormat24h())
                .execute();
    }

    private ServiceView toService(Record r) {
        return new ServiceView(
                r.get(CATALOG_SERVICE.ID),
                r.get(CATALOG_SERVICE.NAME),
                r.get(CATALOG_SERVICE.DESCRIPTION),
                r.get(CATALOG_SERVICE.DURATION_MINUTES),
                r.get(CATALOG_SERVICE.PRICE_MINOR),
                r.get(CATALOG_SERVICE.BUFFER_BEFORE_MINUTES),
                r.get(CATALOG_SERVICE.BUFFER_AFTER_MINUTES),
                r.get(CATALOG_SERVICE.COLOR),
                r.get(CATALOG_SERVICE.ACTIVE),
                r.get(CATALOG_SERVICE.PUBLIC_VISIBLE)
        );
    }

    private SpecialistView toSpecialist(Record r) {
        return new SpecialistView(
                r.get(SPECIALIST.ID),
                r.get(SPECIALIST.DISPLAY_NAME),
                r.get(SPECIALIST.KEYCLOAK_USER_ID),
                r.get(SPECIALIST.CALENDAR_COLOR),
                r.get(SPECIALIST.ACTIVE)
        );
    }

    private MasterServiceView toMatrix(Record r) {
        return new MasterServiceView(
                r.get(MASTER_SERVICE.ID),
                r.get(MASTER_SERVICE.SPECIALIST_ID),
                r.get(MASTER_SERVICE.SERVICE_ID),
                r.get(MASTER_SERVICE.OFFERED),
                r.get(MASTER_SERVICE.DURATION_MINUTES_OVERRIDE),
                r.get(MASTER_SERVICE.PRICE_MINOR_OVERRIDE),
                r.get(MASTER_SERVICE.BUFFER_BEFORE_MINUTES_OVERRIDE),
                r.get(MASTER_SERVICE.BUFFER_AFTER_MINUTES_OVERRIDE)
        );
    }

    private Offering toOffering(MasterServiceView row, ServiceView service) {
        int duration = firstNonNull(row.durationMinutesOverride(), service.durationMinutes());
        int price = firstNonNull(row.priceMinorOverride(), service.priceMinor());
        int before = firstNonNull(row.bufferBeforeMinutesOverride(), service.bufferBeforeMinutes());
        int after = firstNonNull(row.bufferAfterMinutesOverride(), service.bufferAfterMinutes());
        return new Offering(
                row.specialistId(),
                row.serviceId(),
                service.name(),
                row.offered(),
                duration,
                price,
                before,
                after,
                service.color(),
                service.active()
        );
    }

    private static int firstNonNull(Integer override, Integer base) {
        return override != null ? override : base;
    }
}
