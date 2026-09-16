package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.APPOINTMENT;
import static com.cadence.platform.jooq.Tables.APPOINTMENT_EVENT;
import static com.cadence.platform.jooq.Tables.BOOKING_ACCESS_TOKEN;

@Repository
class AppointmentRepository {

    private final DSLContext dsl;

    AppointmentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    void insert(AppointmentView row) {
        dsl.insertInto(APPOINTMENT)
                .set(APPOINTMENT.ID, row.id())
                .set(APPOINTMENT.TENANT_ID, TenantContext.require())
                .set(APPOINTMENT.SPECIALIST_ID, row.specialistId())
                .set(APPOINTMENT.SERVICE_ID, row.serviceId())
                .set(APPOINTMENT.CLIENT_ID, row.clientId())
                .set(APPOINTMENT.STATUS, row.status().name())
                .set(APPOINTMENT.SERVICE_START, Utc.toLocal(row.serviceStart()))
                .set(APPOINTMENT.SERVICE_END, Utc.toLocal(row.serviceEnd()))
                .set(APPOINTMENT.OCCUPIED_START, Utc.toLocal(row.occupiedStart()))
                .set(APPOINTMENT.OCCUPIED_END, Utc.toLocal(row.occupiedEnd()))
                .set(APPOINTMENT.SERVICE_NAME_SNAPSHOT, row.serviceNameSnapshot())
                .set(APPOINTMENT.PRICE_SNAPSHOT, row.priceSnapshot())
                .set(APPOINTMENT.DURATION_SNAPSHOT, row.durationSnapshot())
                .set(APPOINTMENT.BUFFER_BEFORE_SNAPSHOT, row.bufferBeforeSnapshot())
                .set(APPOINTMENT.BUFFER_AFTER_SNAPSHOT, row.bufferAfterSnapshot())
                .set(APPOINTMENT.CURRENCY_CODE, row.currencyCode())
                .set(APPOINTMENT.DISCOUNT_AMOUNT, row.discountAmount())
                .set(APPOINTMENT.AMOUNT_RECEIVED, row.amountReceived())
                .set(APPOINTMENT.ENTERED_NAME, row.enteredName())
                .set(APPOINTMENT.NOTE, row.note())
                .set(APPOINTMENT.SOURCE, row.source())
                .set(APPOINTMENT.CREATED_AT, Utc.toLocal(Instant.now()))
                .set(APPOINTMENT.UPDATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }

    void updateStatusAndTimes(AppointmentView row) {
        dsl.update(APPOINTMENT)
                .set(APPOINTMENT.STATUS, row.status().name())
                .set(APPOINTMENT.SERVICE_START, Utc.toLocal(row.serviceStart()))
                .set(APPOINTMENT.SERVICE_END, Utc.toLocal(row.serviceEnd()))
                .set(APPOINTMENT.OCCUPIED_START, Utc.toLocal(row.occupiedStart()))
                .set(APPOINTMENT.OCCUPIED_END, Utc.toLocal(row.occupiedEnd()))
                .set(APPOINTMENT.NOTE, row.note())
                .set(APPOINTMENT.UPDATED_AT, Utc.toLocal(Instant.now()))
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID).and(APPOINTMENT.ID.eq(row.id())))
                .execute();
    }

    Optional<AppointmentView> find(UUID id) {
        return dsl.selectFrom(APPOINTMENT)
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID).and(APPOINTMENT.ID.eq(id)))
                .fetchOptional(this::toView);
    }

    List<AppointmentView> calendar(Instant from, Instant to, UUID specialistId) {
        var condition = TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID)
                .and(APPOINTMENT.OCCUPIED_START.lt(Utc.toLocal(to)))
                .and(APPOINTMENT.OCCUPIED_END.gt(Utc.toLocal(from)));
        if (specialistId != null) {
            condition = condition.and(APPOINTMENT.SPECIALIST_ID.eq(specialistId));
        }
        return dsl.selectFrom(APPOINTMENT)
                .where(condition)
                .orderBy(APPOINTMENT.SERVICE_START.asc())
                .fetch(this::toView);
    }

    List<SlotCalculator.OccupiedInterval> occupying(UUID specialistId, Instant from, Instant to, UUID excludeId) {
        var statuses = AppointmentStatus.OCCUPYING_STATUSES.stream().map(Enum::name).toList();
        var condition = TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID)
                .and(APPOINTMENT.SPECIALIST_ID.eq(specialistId))
                .and(APPOINTMENT.STATUS.in(statuses))
                .and(APPOINTMENT.OCCUPIED_START.lt(Utc.toLocal(to)))
                .and(APPOINTMENT.OCCUPIED_END.gt(Utc.toLocal(from)));
        if (excludeId != null) {
            condition = condition.and(APPOINTMENT.ID.ne(excludeId));
        }
        return dsl.selectFrom(APPOINTMENT)
                .where(condition)
                .fetch(r -> new SlotCalculator.OccupiedInterval(
                        Utc.toInstant(r.get(APPOINTMENT.OCCUPIED_START)),
                        Utc.toInstant(r.get(APPOINTMENT.OCCUPIED_END))
                ));
    }

    void insertEvent(UUID appointmentId, String type, String payload) {
        dsl.insertInto(APPOINTMENT_EVENT)
                .set(APPOINTMENT_EVENT.ID, UUID.randomUUID())
                .set(APPOINTMENT_EVENT.TENANT_ID, TenantContext.require())
                .set(APPOINTMENT_EVENT.APPOINTMENT_ID, appointmentId)
                .set(APPOINTMENT_EVENT.EVENT_TYPE, type)
                .set(APPOINTMENT_EVENT.PAYLOAD, payload)
                .set(APPOINTMENT_EVENT.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }

    void insertToken(UUID appointmentId, String tokenHash, Instant expiresAt) {
        dsl.insertInto(BOOKING_ACCESS_TOKEN)
                .set(BOOKING_ACCESS_TOKEN.ID, UUID.randomUUID())
                .set(BOOKING_ACCESS_TOKEN.TENANT_ID, TenantContext.require())
                .set(BOOKING_ACCESS_TOKEN.APPOINTMENT_ID, appointmentId)
                .set(BOOKING_ACCESS_TOKEN.TOKEN_HASH, tokenHash)
                .set(BOOKING_ACCESS_TOKEN.EXPIRES_AT, Utc.toLocal(expiresAt))
                .set(BOOKING_ACCESS_TOKEN.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }

    Optional<UUID> appointmentIdByTokenHash(String hash) {
        return dsl.selectFrom(BOOKING_ACCESS_TOKEN)
                .where(BOOKING_ACCESS_TOKEN.TOKEN_HASH.eq(hash)
                        .and(BOOKING_ACCESS_TOKEN.REVOKED_AT.isNull())
                        .and(BOOKING_ACCESS_TOKEN.EXPIRES_AT.gt(Utc.toLocal(Instant.now()))))
                .fetchOptional(BOOKING_ACCESS_TOKEN.APPOINTMENT_ID);
    }

    Optional<UUID> tenantIdByTokenHash(String hash) {
        return dsl.selectFrom(BOOKING_ACCESS_TOKEN)
                .where(BOOKING_ACCESS_TOKEN.TOKEN_HASH.eq(hash))
                .fetchOptional(BOOKING_ACCESS_TOKEN.TENANT_ID);
    }

    private AppointmentView toView(Record r) {
        return new AppointmentView(
                r.get(APPOINTMENT.ID),
                r.get(APPOINTMENT.SPECIALIST_ID),
                r.get(APPOINTMENT.SERVICE_ID),
                r.get(APPOINTMENT.CLIENT_ID),
                AppointmentStatus.valueOf(r.get(APPOINTMENT.STATUS)),
                Utc.toInstant(r.get(APPOINTMENT.SERVICE_START)),
                Utc.toInstant(r.get(APPOINTMENT.SERVICE_END)),
                Utc.toInstant(r.get(APPOINTMENT.OCCUPIED_START)),
                Utc.toInstant(r.get(APPOINTMENT.OCCUPIED_END)),
                r.get(APPOINTMENT.SERVICE_NAME_SNAPSHOT),
                r.get(APPOINTMENT.PRICE_SNAPSHOT),
                r.get(APPOINTMENT.DURATION_SNAPSHOT),
                r.get(APPOINTMENT.BUFFER_BEFORE_SNAPSHOT),
                r.get(APPOINTMENT.BUFFER_AFTER_SNAPSHOT),
                r.get(APPOINTMENT.CURRENCY_CODE),
                r.get(APPOINTMENT.DISCOUNT_AMOUNT),
                r.get(APPOINTMENT.AMOUNT_RECEIVED),
                r.get(APPOINTMENT.ENTERED_NAME),
                r.get(APPOINTMENT.NOTE),
                r.get(APPOINTMENT.SOURCE),
                r.get(APPOINTMENT.ENTERED_NAME),
                null,
                null
        );
    }
}
