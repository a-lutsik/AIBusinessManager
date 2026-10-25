package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentEventType;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.JournalDefect;
import com.cadence.booking.api.JournalFold;
import com.cadence.booking.api.VisitFact;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure fold of the booking journal into visit facts (ADR 0001 §3), no I/O.
 *
 * <p>Events of one visit are applied in {@code (created_at, id)} order, ids compared as PostgreSQL compares
 * {@code uuid} (unsigned, byte by byte), so this fold and an SQL replay order ties the same way. Events with
 * {@code created_at > asOf} are ignored. The first event of a visit must be CREATED, otherwise the visit gets a
 * {@code NO_CREATED} defect and no fact. Every later event checks its {@code from} against the folded value: a
 * mismatch is a {@code CHAIN_BREAK} defect and the event is still applied, never silently.
 *
 * <p>An event type unknown to {@link AppointmentEventType}, a payload the reader refuses, or a reserved type
 * (MASTER/SERVICE/PRICE_CHANGED, not emitted yet) throws {@link IllegalStateException}: the tenant's recalculation
 * fails loudly.
 */
final class VisitJournalFold {

    /** One {@code appointment_event} row. */
    record EventRow(UUID id, UUID appointmentId, String type, String payload, Instant createdAt) {
    }

    /** PostgreSQL's {@code uuid} order: unsigned comparison of the 16 bytes, most significant first. */
    static final Comparator<UUID> PG_UUID_ORDER = (a, b) -> {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    };

    static final Comparator<EventRow> JOURNAL_ORDER = Comparator.comparing(EventRow::createdAt)
            .thenComparing(EventRow::id, PG_UUID_ORDER);

    private VisitJournalFold() {
    }

    static JournalFold fold(List<EventRow> events, Instant asOf) {
        Map<UUID, List<EventRow>> byVisit = new LinkedHashMap<>();
        events.stream()
                .filter(e -> !e.createdAt().isAfter(asOf))
                .sorted(Comparator.comparing(EventRow::appointmentId, PG_UUID_ORDER).thenComparing(JOURNAL_ORDER))
                .forEach(e -> byVisit.computeIfAbsent(e.appointmentId(), k -> new ArrayList<>()).add(e));
        List<VisitFact> facts = new ArrayList<>();
        List<JournalDefect> defects = new ArrayList<>();
        for (Map.Entry<UUID, List<EventRow>> visit : byVisit.entrySet()) {
            VisitFact fact = foldVisit(visit.getKey(), visit.getValue(), defects);
            if (fact != null) {
                facts.add(fact);
            }
        }
        return new JournalFold(asOf, facts, defects);
    }

    private static VisitFact foldVisit(UUID appointmentId, List<EventRow> events, List<JournalDefect> defects) {
        EventRow first = events.getFirst();
        AppointmentEventType firstType = typeOf(first);
        if (firstType != AppointmentEventType.CREATED) {
            defects.add(new JournalDefect(appointmentId, JournalDefect.NO_CREATED,
                    "first event " + first.id() + " is " + firstType));
            // Still read every event, so an unknown type or reserved type fails loudly here too.
            events.forEach(e -> typeOf(e));
            return null;
        }
        JournalPayloads.Created created = (JournalPayloads.Created) JournalPayloads.parse(firstType, first.payload());
        State state = new State(created.status(), created.serviceStart(), created.serviceEnd(), created.clientId());
        for (EventRow event : events.subList(1, events.size())) {
            state = apply(appointmentId, created, state, event, defects);
        }
        return new VisitFact(
                appointmentId,
                created.specialistId(),
                created.serviceId(),
                state.clientId(),
                state.status(),
                state.start(),
                state.end(),
                created.priceMinor(),
                created.discountMinor(),
                created.durationMinutes(),
                created.currency(),
                created.serviceName(),
                created.source(),
                first.createdAt()
        );
    }

    /** The folded, changing facts of a visit. */
    private record State(AppointmentStatus status, Instant start, Instant end, UUID clientId) {
    }

    /**
     * One event after CREATED. A switch expression without {@code default} (review D2): a new
     * {@link AppointmentEventType} constant does not compile until this fold decides what it means.
     */
    private static State apply(UUID appointmentId, JournalPayloads.Created created, State state, EventRow event,
                               List<JournalDefect> defects) {
        AppointmentEventType type = typeOf(event);
        return switch (type) {
            case CREATED -> {
                defects.add(new JournalDefect(appointmentId, JournalDefect.CHAIN_BREAK, "second CREATED " + event.id()));
                yield state;
            }
            case CONFIRMED, COMPLETED, NO_SHOW, CANCELLED -> {
                JournalPayloads.StatusChange change = (JournalPayloads.StatusChange) JournalPayloads.parse(type, event.payload());
                if (change.from() != state.status()) {
                    defects.add(new JournalDefect(appointmentId, JournalDefect.CHAIN_BREAK,
                            type + " " + event.id() + " from " + change.from() + " but status was " + state.status()));
                }
                if (JournalPayloads.eventTypeFor(change.to()) != type) {
                    defects.add(new JournalDefect(appointmentId, JournalDefect.CHAIN_BREAK,
                            type + " " + event.id() + " sets status " + change.to()));
                }
                yield new State(change.to(), state.start(), state.end(), state.clientId());
            }
            case RESCHEDULED -> {
                JournalPayloads.Rescheduled move = (JournalPayloads.Rescheduled) JournalPayloads.parse(type, event.payload());
                if (!move.from().equals(state.start())) {
                    defects.add(new JournalDefect(appointmentId, JournalDefect.CHAIN_BREAK,
                            "RESCHEDULED " + event.id() + " from " + move.from() + " but start was " + state.start()));
                }
                yield new State(state.status(), move.to(), move.to().plus(Duration.ofMinutes(created.durationMinutes())),
                        state.clientId());
            }
            case CLIENT_CHANGED -> {
                JournalPayloads.ClientChanged change = (JournalPayloads.ClientChanged) JournalPayloads.parse(type, event.payload());
                if (!change.from().equals(state.clientId())) {
                    defects.add(new JournalDefect(appointmentId, JournalDefect.CHAIN_BREAK,
                            "CLIENT_CHANGED " + event.id() + " from " + change.from() + " but client was " + state.clientId()));
                }
                yield new State(state.status(), state.start(), state.end(), change.to());
            }
            case MASTER_CHANGED, SERVICE_CHANGED, PRICE_CHANGED ->
                    throw new IllegalStateException(type + " is not emitted yet, event " + event.id()
                            + " of visit " + appointmentId + " cannot be folded");
            case REMINDER_SENT -> state;
        };
    }

    private static AppointmentEventType typeOf(EventRow event) {
        try {
            return AppointmentEventType.valueOf(event.type());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Unknown journal event type '" + event.type() + "' (event " + event.id()
                    + " of visit " + event.appointmentId() + ")", ex);
        }
    }
}
