package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.BookingJournal;
import com.cadence.booking.api.JournalDefect;
import com.cadence.booking.api.JournalFold;
import com.cadence.booking.api.VisitFact;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.APPOINTMENT;
import static com.cadence.platform.jooq.Tables.APPOINTMENT_EVENT;

/** jOOQ side of {@link BookingJournal}: reads the tenant's journal and rows, the fold itself is {@link VisitJournalFold}. */
@Service
public class BookingJournalImpl implements BookingJournal {

    private final DSLContext dsl;

    public BookingJournalImpl(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional(readOnly = true)
    public JournalFold fold(Instant asOf) {
        assertOwnerOrSystem();
        return VisitJournalFold.fold(events(asOf), asOf);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalDefect> reconcile(Instant asOf) {
        assertOwnerOrSystem();
        JournalFold fold = VisitJournalFold.fold(events(asOf), asOf);
        List<JournalDefect> defects = new ArrayList<>(fold.defects());
        Set<UUID> flagged = new HashSet<>();
        fold.defects().stream()
                .filter(d -> JournalDefect.NO_CREATED.equals(d.kind()))
                .forEach(d -> flagged.add(d.appointmentId()));
        Map<UUID, VisitFact> facts = new LinkedHashMap<>();
        fold.facts().forEach(f -> facts.put(f.appointmentId(), f));
        Set<UUID> journaled = new HashSet<>(facts.keySet());
        journaled.addAll(flagged);

        Set<UUID> rowIds = new HashSet<>();
        dsl.selectFrom(APPOINTMENT)
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT.TENANT_ID)
                        .and(APPOINTMENT.CREATED_AT.le(Utc.toLocal(asOf))))
                .orderBy(APPOINTMENT.ID)
                .forEach(r -> {
                    UUID id = r.get(APPOINTMENT.ID);
                    rowIds.add(id);
                    VisitFact fact = facts.get(id);
                    if (fact == null) {
                        if (!flagged.contains(id)) {
                            defects.add(new JournalDefect(id, JournalDefect.NO_CREATED, "visit row without journal events"));
                        }
                        return;
                    }
                    List<String> fields = new ArrayList<>();
                    compare(fields, "specialistId", fact.specialistId(), r.get(APPOINTMENT.SPECIALIST_ID));
                    compare(fields, "serviceId", fact.serviceId(), r.get(APPOINTMENT.SERVICE_ID));
                    compare(fields, "clientId", fact.clientId(), r.get(APPOINTMENT.CLIENT_ID));
                    compare(fields, "status", fact.status(), AppointmentStatus.valueOf(r.get(APPOINTMENT.STATUS)));
                    compare(fields, "serviceStart", fact.serviceStart(), Utc.toInstant(r.get(APPOINTMENT.SERVICE_START)));
                    compare(fields, "serviceEnd", fact.serviceEnd(), Utc.toInstant(r.get(APPOINTMENT.SERVICE_END)));
                    compare(fields, "priceMinor", fact.priceMinor(), r.get(APPOINTMENT.PRICE_SNAPSHOT));
                    compare(fields, "discountMinor", fact.discountMinor(), r.get(APPOINTMENT.DISCOUNT_AMOUNT));
                    compare(fields, "durationMinutes", fact.durationMinutes(), r.get(APPOINTMENT.DURATION_SNAPSHOT));
                    compare(fields, "currency", fact.currencyCode(), r.get(APPOINTMENT.CURRENCY_CODE));
                    compare(fields, "serviceName", fact.serviceName(), r.get(APPOINTMENT.SERVICE_NAME_SNAPSHOT));
                    compare(fields, "source", fact.source(), r.get(APPOINTMENT.SOURCE));
                    if (!fields.isEmpty()) {
                        defects.add(new JournalDefect(id, JournalDefect.ROW_MISMATCH, String.join("; ", fields)));
                    }
                });
        journaled.stream()
                .filter(id -> !rowIds.contains(id))
                .sorted(VisitJournalFold.PG_UUID_ORDER)
                .forEach(id -> defects.add(new JournalDefect(id, JournalDefect.ORPHAN_EVENT, "journal events without a visit row")));
        return defects;
    }

    private static void compare(List<String> mismatches, String field, Object journal, Object row) {
        if (!Objects.equals(journal, row)) {
            mismatches.add(field + ": journal " + journal + ", row " + row);
        }
    }

    private List<VisitJournalFold.EventRow> events(Instant asOf) {
        return dsl.select(APPOINTMENT_EVENT.ID, APPOINTMENT_EVENT.APPOINTMENT_ID, APPOINTMENT_EVENT.EVENT_TYPE,
                        APPOINTMENT_EVENT.PAYLOAD, APPOINTMENT_EVENT.CREATED_AT)
                .from(APPOINTMENT_EVENT)
                .where(TenantAwareDsl.tenantEquals(APPOINTMENT_EVENT.TENANT_ID)
                        .and(APPOINTMENT_EVENT.CREATED_AT.le(Utc.toLocal(asOf))))
                .orderBy(APPOINTMENT_EVENT.APPOINTMENT_ID, APPOINTMENT_EVENT.CREATED_AT, APPOINTMENT_EVENT.ID)
                .fetch(r -> new VisitJournalFold.EventRow(
                        r.get(APPOINTMENT_EVENT.ID),
                        r.get(APPOINTMENT_EVENT.APPOINTMENT_ID),
                        r.get(APPOINTMENT_EVENT.EVENT_TYPE),
                        r.get(APPOINTMENT_EVENT.PAYLOAD),
                        Utc.toInstant(r.get(APPOINTMENT_EVENT.CREATED_AT))
                ));
    }

    private static void assertOwnerOrSystem() {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor == null || !(actor.isOwner() || actor.role() == ActorContext.Role.SYSTEM)) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Only the owner or the system reads the booking journal");
        }
    }
}
