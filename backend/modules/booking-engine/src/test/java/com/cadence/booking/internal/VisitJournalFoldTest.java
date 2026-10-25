package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentEventType;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.JournalDefect;
import com.cadence.booking.api.JournalFold;
import com.cadence.booking.api.VisitFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pure fold of ADR 0001 §3: one visit's events in, one fact (or a defect) out. */
class VisitJournalFoldTest {

    private static final UUID VISIT = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MASTER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID SURVIVOR = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant START = Instant.parse("2026-09-10T10:00:00Z");

    private final List<VisitJournalFold.EventRow> events = new ArrayList<>();

    private static String created(AppointmentStatus status) {
        return JournalPayloads.toJson(new JournalPayloads.Created(MASTER, SERVICE, CLIENT, status, START,
                START.plusSeconds(45 * 60), 8000, 500, 45, "GEL", "Haircut", "CONSOLE"));
    }

    private void add(String type, String payload, Instant at) {
        events.add(new VisitJournalFold.EventRow(UUID.randomUUID(), VISIT, type, payload, at));
    }

    private void addStatus(AppointmentStatus from, AppointmentStatus to, Instant at) {
        add(JournalPayloads.eventTypeFor(to).name(),
                JournalPayloads.toJson(new JournalPayloads.StatusChange(from, to, false)), at);
    }

    private JournalFold fold(Instant asOf) {
        return VisitJournalFold.fold(events, asOf);
    }

    private VisitFact onlyFact(Instant asOf) {
        JournalFold fold = fold(asOf);
        assertThat(fold.defects()).isEmpty();
        assertThat(fold.facts()).hasSize(1);
        return fold.facts().getFirst();
    }

    @Test
    void createdAloneGivesTheCreatedFacts() {
        add("CREATED", created(AppointmentStatus.CONFIRMED), T0);

        VisitFact fact = onlyFact(T0);

        assertThat(fact).isEqualTo(new VisitFact(VISIT, MASTER, SERVICE, CLIENT, AppointmentStatus.CONFIRMED, START,
                START.plusSeconds(2700), 8000, 500, 45, "GEL", "Haircut", "CONSOLE", T0));
    }

    @Test
    void statusEventsMoveTheStatusInJournalOrderWhateverTheInputOrder() {
        addStatus(AppointmentStatus.CONFIRMED, AppointmentStatus.COMPLETED, T0.plusSeconds(120));
        add("CREATED", created(AppointmentStatus.PENDING), T0);
        addStatus(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, T0.plusSeconds(60));

        assertThat(onlyFact(T0.plusSeconds(500)).status()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    void asOfLeavesLaterEventsOut() {
        add("CREATED", created(AppointmentStatus.PENDING), T0);
        addStatus(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, T0.plusSeconds(60));
        addStatus(AppointmentStatus.CONFIRMED, AppointmentStatus.NO_SHOW, T0.plusSeconds(120));

        assertThat(onlyFact(T0.plusSeconds(60)).status()).as("an event at exactly asOf counts").isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(onlyFact(T0.plusSeconds(119)).status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(fold(T0.minusSeconds(1)).facts()).as("not created yet").isEmpty();
    }

    @Test
    void rescheduleMovesStartAndEndByTheCreatedDuration() {
        Instant moved = Instant.parse("2026-09-12T14:30:00Z");
        add("CREATED", created(AppointmentStatus.CONFIRMED), T0);
        add("RESCHEDULED", JournalPayloads.toJson(new JournalPayloads.Rescheduled(START, moved)), T0.plusSeconds(10));

        VisitFact fact = onlyFact(T0.plusSeconds(10));

        assertThat(fact.serviceStart()).isEqualTo(moved);
        assertThat(fact.serviceEnd()).isEqualTo(moved.plusSeconds(45 * 60));
    }

    @Test
    void clientChangedMovesTheVisitToTheSurvivor() {
        add("CREATED", created(AppointmentStatus.CONFIRMED), T0);
        add("CLIENT_CHANGED", JournalPayloads.toJson(new JournalPayloads.ClientChanged(CLIENT, SURVIVOR, "MERGE")), T0.plusSeconds(5));

        assertThat(onlyFact(T0.plusSeconds(5)).clientId()).isEqualTo(SURVIVOR);
    }

    @Test
    void ownerCorrectionIsAStatusEventLikeAnyOther() {
        add("CREATED", created(AppointmentStatus.CONFIRMED), T0);
        addStatus(AppointmentStatus.CONFIRMED, AppointmentStatus.COMPLETED, T0.plusSeconds(1));
        add("NO_SHOW", JournalPayloads.toJson(new JournalPayloads.StatusChange(AppointmentStatus.COMPLETED,
                AppointmentStatus.NO_SHOW, true)), T0.plusSeconds(2));

        assertThat(onlyFact(T0.plusSeconds(2)).status()).isEqualTo(AppointmentStatus.NO_SHOW);
    }

    @Test
    void tiesOnCreatedAtAreOrderedByIdAsPostgresOrdersUuids() {
        // Signed Java order puts 8000... first, PostgreSQL's unsigned order puts 0000... first.
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000001");
        assertThat(high.compareTo(low)).as("java.util.UUID compares signed").isNegative();
        add("CREATED", created(AppointmentStatus.PENDING), T0);
        Instant tie = T0.plusSeconds(30);
        events.add(new VisitJournalFold.EventRow(high, VISIT, "COMPLETED",
                JournalPayloads.toJson(new JournalPayloads.StatusChange(AppointmentStatus.CONFIRMED, AppointmentStatus.COMPLETED, false)), tie));
        events.add(new VisitJournalFold.EventRow(low, VISIT, "CONFIRMED",
                JournalPayloads.toJson(new JournalPayloads.StatusChange(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, false)), tie));

        assertThat(onlyFact(tie).status()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    void aVisitWhoseFirstEventIsNotCreatedIsADefectWithoutAFact() {
        addStatus(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, T0);

        JournalFold fold = fold(T0);

        assertThat(fold.facts()).isEmpty();
        assertThat(fold.defects()).singleElement().satisfies(d -> {
            assertThat(d.appointmentId()).isEqualTo(VISIT);
            assertThat(d.kind()).isEqualTo(JournalDefect.NO_CREATED);
        });
    }

    @Test
    void aStatusEventFromTheWrongStatusIsAChainBreakAndIsStillApplied() {
        add("CREATED", created(AppointmentStatus.PENDING), T0);
        addStatus(AppointmentStatus.CONFIRMED, AppointmentStatus.COMPLETED, T0.plusSeconds(1));

        JournalFold fold = fold(T0.plusSeconds(1));

        assertThat(fold.facts()).singleElement().extracting(VisitFact::status).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(fold.defects()).singleElement().extracting(JournalDefect::kind).isEqualTo(JournalDefect.CHAIN_BREAK);
    }

    @Test
    void aMoveOrClientChangeFromTheWrongValueIsAChainBreak() {
        add("CREATED", created(AppointmentStatus.CONFIRMED), T0);
        Instant elsewhere = START.plusSeconds(3600);
        add("RESCHEDULED", JournalPayloads.toJson(new JournalPayloads.Rescheduled(elsewhere, elsewhere.plusSeconds(600))), T0.plusSeconds(1));
        add("CLIENT_CHANGED", JournalPayloads.toJson(new JournalPayloads.ClientChanged(SURVIVOR, CLIENT, "MERGE")), T0.plusSeconds(2));

        JournalFold fold = fold(T0.plusSeconds(2));

        assertThat(fold.defects()).extracting(JournalDefect::kind)
                .containsExactly(JournalDefect.CHAIN_BREAK, JournalDefect.CHAIN_BREAK);
        assertThat(fold.facts().getFirst().serviceStart()).isEqualTo(elsewhere.plusSeconds(600));
    }

    @Test
    void reminderSentChangesNoFact() {
        add("CREATED", created(AppointmentStatus.CONFIRMED), T0);
        add("REMINDER_SENT", "{\"v\":2}", T0.plusSeconds(1));

        assertThat(onlyFact(T0.plusSeconds(1)).status()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void anUnknownEventTypeFailsLoudly() {
        add("CREATED", created(AppointmentStatus.CONFIRMED), T0);
        add("PAID", "{\"v\":2}", T0.plusSeconds(1));

        assertThatThrownBy(() -> fold(T0.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAID");
    }

    @Test
    void reservedTypesAreRefusedUntilAWritePathEmitsThem() {
        for (AppointmentEventType reserved : List.of(AppointmentEventType.MASTER_CHANGED,
                AppointmentEventType.SERVICE_CHANGED, AppointmentEventType.PRICE_CHANGED)) {
            events.clear();
            add("CREATED", created(AppointmentStatus.CONFIRMED), T0);
            add(reserved.name(), "{\"v\":2}", T0.plusSeconds(1));

            assertThatThrownBy(() -> fold(T0.plusSeconds(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not emitted yet");
        }
    }

    @Test
    void aCreatedPayloadThatIsNotV2FailsLoudly() {
        add("CREATED", "{\"status\":\"CONFIRMED\"}", T0);

        assertThatThrownBy(() -> fold(T0)).isInstanceOf(IllegalStateException.class).hasMessageContaining("v2");
    }

    /** The shape V13 writes with json_build_object: spaces, microsecond instants, an extra "backfilled" flag. */
    @Test
    void aBackfilledCreatedPayloadIsRead() {
        add("CREATED", "{\"v\" : 2, \"specialistId\" : \"" + MASTER + "\", \"serviceId\" : \"" + SERVICE
                + "\", \"clientId\" : \"" + CLIENT + "\", \"status\" : \"PENDING\", \"serviceStart\" : "
                + "\"2026-09-10T10:00:00.000000Z\", \"serviceEnd\" : \"2026-09-10T10:45:00.000000Z\", \"priceMinor\" : "
                + "8000, \"discountMinor\" : 500, \"durationMinutes\" : 45, \"currency\" : \"GEL\", \"serviceName\" : "
                + "\"Haircut\", \"source\" : \"CONSOLE\", \"backfilled\" : true}", T0);

        assertThat(onlyFact(T0)).isEqualTo(new VisitFact(VISIT, MASTER, SERVICE, CLIENT, AppointmentStatus.PENDING,
                START, START.plusSeconds(2700), 8000, 500, 45, "GEL", "Haircut", "CONSOLE", T0));
    }

    @Test
    void statusAndMovePayloadsWrittenBeforeV13AreRead() {
        Instant moved = Instant.parse("2026-09-11T09:00:00Z");
        add("CREATED", created(AppointmentStatus.PENDING), T0);
        add("CONFIRMED", "{\"from\":\"PENDING\",\"to\":\"CONFIRMED\"}", T0.plusSeconds(1));
        add("RESCHEDULED", "{\"from\":\"" + START + "\",\"to\":\"" + moved + "\"}", T0.plusSeconds(2));

        VisitFact fact = onlyFact(T0.plusSeconds(2));

        assertThat(fact.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(fact.serviceStart()).isEqualTo(moved);
    }

    @Test
    void everyWrittenPayloadIsVersion2AndReadsBackAsWritten() {
        var payloads = List.<JournalPayloads.Payload>of(
                new JournalPayloads.Created(MASTER, SERVICE, CLIENT, AppointmentStatus.PENDING, START,
                        START.plusSeconds(60), 0, 0, 1, "CZK", "Cut \"quoted\" \\ name", "PUBLIC"),
                new JournalPayloads.StatusChange(AppointmentStatus.NO_SHOW, AppointmentStatus.COMPLETED, true),
                new JournalPayloads.Rescheduled(START, START.plusSeconds(1800)),
                new JournalPayloads.ClientChanged(CLIENT, SURVIVOR, "MERGE"));
        List<AppointmentEventType> types = List.of(AppointmentEventType.CREATED, AppointmentEventType.COMPLETED,
                AppointmentEventType.RESCHEDULED, AppointmentEventType.CLIENT_CHANGED);
        for (int i = 0; i < payloads.size(); i++) {
            String json = JournalPayloads.toJson(payloads.get(i));
            assertThat(json).contains("\"v\":2");
            assertThat(JournalPayloads.parse(types.get(i), json)).isEqualTo(payloads.get(i));
        }
        assertThat(JournalPayloads.toJson(new JournalPayloads.StatusChange(AppointmentStatus.PENDING,
                AppointmentStatus.CONFIRMED, false))).doesNotContain("correction");
    }

    @Test
    void aPayloadOfTheWrongShapeIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> JournalPayloads.requireFits(AppointmentEventType.CONFIRMED,
                new JournalPayloads.StatusChange(AppointmentStatus.CONFIRMED, AppointmentStatus.COMPLETED, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JournalPayloads.requireFits(AppointmentEventType.CREATED,
                new JournalPayloads.Rescheduled(START, START)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
