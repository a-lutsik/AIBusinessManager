package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.persistence.SqlStates;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.APPOINTMENT;
import static com.cadence.platform.jooq.Tables.APPOINTMENT_EVENT;
import static com.cadence.platform.jooq.Tables.BOOKING_ACCESS_TOKEN;

@Repository
class AppointmentRepository {

    /** GiST exclusion constraint from V4: no two active visits of one specialist may overlap in occupied time. */
    static final String NO_OVERLAP_CONSTRAINT = "no_overlapping_active_appointments";

    private final DSLContext dsl;

    AppointmentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Inserts the visit. The application-level free-slot check is only advisory: two requests can both pass it, and
     * the exclusion constraint then rejects the later writer. That rejection is reported as {@code SLOT_OCCUPIED}
     * (409), the same answer the loser would have got had it arrived a moment later.
     */
    void insert(AppointmentView row) {
        try {
            doInsert(row);
        } catch (RuntimeException ex) {
            throw slotConflictOrSame(ex);
        }
    }

    private void doInsert(AppointmentView row) {
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
                .set(APPOINTMENT.CLIENT_HIDDEN_FROM_MASTER, row.clientHiddenFromMaster())
                .set(APPOINTMENT.CREATED_AT, Utc.toLocal(Instant.now()))
                .set(APPOINTMENT.UPDATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }

    /**
     * Updates status and times, but only while the stored status is still {@code expectedStatus}: a concurrent writer
     * that got there first leaves zero matching rows, reported as {@code CONCURRENT_MODIFICATION} (409) so the caller
     * does not apply its side effects (CRM aggregates) a second time. Moving or reviving a visit into an occupied
     * slot is reported as {@code SLOT_OCCUPIED}. Writes {@code clientHiddenFromMaster} as given (decision A: the
     * owner's confirmation clears it), so every caller passes the current flag on unless it means to change it.
     */
    void updateStatusAndTimes(AppointmentView row, AppointmentStatus expectedStatus) {
        int updated;
        try {
            updated = doUpdateStatusAndTimes(row, expectedStatus);
        } catch (RuntimeException ex) {
            throw slotConflictOrSame(ex);
        }
        if (updated == 0) {
            throw DomainException.conflict("CONCURRENT_MODIFICATION", "The visit was changed by someone else, reload and retry");
        }
    }

    private int doUpdateStatusAndTimes(AppointmentView row, AppointmentStatus expectedStatus) {
        return dsl.update(APPOINTMENT)
                .set(APPOINTMENT.STATUS, row.status().name())
                .set(APPOINTMENT.SERVICE_START, Utc.toLocal(row.serviceStart()))
                .set(APPOINTMENT.SERVICE_END, Utc.toLocal(row.serviceEnd()))
                .set(APPOINTMENT.OCCUPIED_START, Utc.toLocal(row.occupiedStart()))
                .set(APPOINTMENT.OCCUPIED_END, Utc.toLocal(row.occupiedEnd()))
                .set(APPOINTMENT.NOTE, row.note())
                .set(APPOINTMENT.CLIENT_HIDDEN_FROM_MASTER, row.clientHiddenFromMaster())
                .set(APPOINTMENT.UPDATED_AT, Utc.toLocal(Instant.now()))
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID)
                        .and(APPOINTMENT.ID.eq(row.id()))
                        .and(APPOINTMENT.STATUS.eq(expectedStatus.name())))
                .execute();
    }

    private static RuntimeException slotConflictOrSame(RuntimeException ex) {
        if (SqlStates.isExclusionViolation(ex, NO_OVERLAP_CONSTRAINT)) {
            DomainException conflict = DomainException.conflict("SLOT_OCCUPIED", "Slot is not available");
            conflict.initCause(ex);
            return conflict;
        }
        return ex;
    }

    int reassignClient(UUID from, UUID to) {
        return dsl.update(APPOINTMENT)
                .set(APPOINTMENT.CLIENT_ID, to)
                .set(APPOINTMENT.UPDATED_AT, Utc.toLocal(Instant.now()))
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID).and(APPOINTMENT.CLIENT_ID.eq(from)))
                .execute();
    }

    /** Earliest and latest service start among the client's COMPLETED visits, leaving one visit out. */
    record CompletedRange(Instant first, Instant last) {
    }

    CompletedRange completedRangeExcluding(UUID clientId, UUID excludeAppointmentId) {
        var min = DSL.min(APPOINTMENT.SERVICE_START);
        var max = DSL.max(APPOINTMENT.SERVICE_START);
        return dsl.select(min, max)
                .from(APPOINTMENT)
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID)
                        .and(APPOINTMENT.CLIENT_ID.eq(clientId))
                        .and(APPOINTMENT.STATUS.eq(AppointmentStatus.COMPLETED.name()))
                        .and(APPOINTMENT.ID.ne(excludeAppointmentId)))
                .fetchOne(r -> new CompletedRange(
                        r.get(min) == null ? null : Utc.toInstant(r.get(min)),
                        r.get(max) == null ? null : Utc.toInstant(r.get(max))
                ));
    }

    Optional<AppointmentView> find(UUID id) {
        return dsl.selectFrom(APPOINTMENT)
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID).and(APPOINTMENT.ID.eq(id)))
                .fetchOptional(this::toView);
    }

    /**
     * Loads the visit and locks its row until the transaction ends, so concurrent status changes of one visit are
     * serialised: the second one re-reads the status the first one wrote.
     */
    Optional<AppointmentView> findForUpdate(UUID id) {
        return dsl.selectFrom(APPOINTMENT)
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID).and(APPOINTMENT.ID.eq(id)))
                .forUpdate()
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

    /** Statuses that give a master access to the client: every status except the two cancellations (TD-19). */
    private static final List<String> SCOPE_STATUSES = Arrays.stream(AppointmentStatus.values())
            .filter(s -> s != AppointmentStatus.CANCELLED_BY_CLIENT && s != AppointmentStatus.CANCELLED_BY_BUSINESS)
            .map(Enum::name)
            .toList();

    /**
     * Visits that put their client into the master's scope: not cancelled (TD-19) and not hidden from the master
     * (decision A: a phone booking onto a client outside the scope waits for the owner's confirmation).
     */
    private static Condition inMasterScope() {
        return APPOINTMENT.STATUS.in(SCOPE_STATUSES).and(APPOINTMENT.CLIENT_HIDDEN_FROM_MASTER.isFalse());
    }

    /**
     * Decision A: the owner confirmed one of the master's hidden phone bookings with this client, so the client is now
     * in that master's scope and the master's other hidden visits with the client are revealed too. Returns how many.
     */
    int revealToMaster(UUID clientId, UUID specialistId) {
        return dsl.update(APPOINTMENT)
                .set(APPOINTMENT.CLIENT_HIDDEN_FROM_MASTER, false)
                .set(APPOINTMENT.UPDATED_AT, Utc.toLocal(Instant.now()))
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID)
                        .and(APPOINTMENT.CLIENT_ID.eq(clientId))
                        .and(APPOINTMENT.SPECIALIST_ID.eq(specialistId))
                        .and(APPOINTMENT.CLIENT_HIDDEN_FROM_MASTER.isTrue()))
                .execute();
    }

    /** Distinct clients with at least one scope-giving visit with {@code specialistId} (a master's client scope). */
    List<UUID> clientIdsOf(UUID specialistId) {
        return dsl.selectDistinct(APPOINTMENT.CLIENT_ID)
                .from(APPOINTMENT)
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID)
                        .and(APPOINTMENT.SPECIALIST_ID.eq(specialistId))
                        .and(inMasterScope()))
                .fetch(APPOINTMENT.CLIENT_ID);
    }

    /** Whether {@code clientId} has a scope-giving visit with {@code specialistId}, i.e. is in that master's scope. */
    boolean hasActiveVisit(UUID clientId, UUID specialistId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(APPOINTMENT)
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID)
                        .and(APPOINTMENT.CLIENT_ID.eq(clientId))
                        .and(APPOINTMENT.SPECIALIST_ID.eq(specialistId))
                        .and(inMasterScope())));
    }

    /**
     * Visits of one client, oldest first; {@code specialistId} narrows them to one specialist when not null, and
     * {@code masterScopeOnly} keeps only the visits that give a master access (no cancellations, nothing hidden from
     * the master): a master's view of the client.
     */
    List<AppointmentView> byClient(UUID clientId, UUID specialistId, boolean masterScopeOnly) {
        var condition = TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID).and(APPOINTMENT.CLIENT_ID.eq(clientId));
        if (specialistId != null) {
            condition = condition.and(APPOINTMENT.SPECIALIST_ID.eq(specialistId));
        }
        if (masterScopeOnly) {
            condition = condition.and(inMasterScope());
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

    /** Tenant and visit behind a live (non-revoked, non-expired) booking token. */
    record TokenTarget(UUID tenantId, UUID appointmentId) {
    }

    /**
     * Cross-tenant lookup: public booking links carry no tenant, so this runs before any tenant scope is open.
     * RLS would hide the row from the runtime role, hence the SECURITY DEFINER function from V7.
     */
    Optional<TokenTarget> resolveToken(String hash) {
        return dsl.resultQuery("select tenant_id, appointment_id from booking_token_lookup({0})", DSL.val(hash))
                .fetchOptional(r -> new TokenTarget(r.get(0, UUID.class), r.get(1, UUID.class)));
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
                null,
                Boolean.TRUE.equals(r.get(APPOINTMENT.CLIENT_HIDDEN_FROM_MASTER))
        );
    }
}
