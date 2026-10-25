package com.cadence.app;

import com.cadence.app.usecase.ClientMergeService;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingJournal;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.booking.api.JournalDefect;
import com.cadence.booking.api.PublicBookingResult;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.MasterServiceView;
import com.cadence.catalog.api.ScheduleExceptionView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import static com.cadence.app.TestJwt.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR 0001 §6 end to end, against the real schema and the RLS runtime role: every write path of visits runs through
 * the real services (console create in every status, confirm, complete, no-show, both owner corrections, cancel,
 * console reschedule, public booking, reschedule and cancel by token, client merge), each writes exactly one journal
 * event per changed visit, then {@code POST /metrics/recalculate}; the independent SQL replay
 * ({@code metrics-replay.sql}, journal and schedule only) must equal every stored snapshot within 1e-9, also for an
 * older snapshot after later corrections. {@code reconcile} finds no defect; repeated recalculations neither duplicate
 * nor revive signals; a row changed behind the journal's back is audited as {@code growth.journal_mismatch}.
 * Review fixes: T = now minus {@code app.metrics.as-of-lag} and an event stamped inside the lag is left out by fold and
 * replay alike (M3); a period's signal resolves itself when its rule stops firing and re-opens when it fires again,
 * never touching {@code dismissed_at} (m4); concurrent recalculations and a dismiss all succeed (m1).
 *
 * <p>Runs in its own tenant. The tenant zone is chosen so that "now" is at least 20 hours into its month and 2 hours
 * before its end, so the current month to date always holds the visits of the last 18 hours; the schedule exceptions
 * sit on fixed dates (previous month, first of the month), so no fixture depends on the time of day (review M2).
 */
@SpringBootTest
@RelaxedPublicRateLimits
@AutoConfigureMockMvc
class MetricsJournalReplayIT extends AbstractTimescaleIT {


    @Autowired
    MockMvc mvc;
    @Autowired
    TenantDirectory tenants;
    @Autowired
    CatalogService catalog;
    @Autowired
    BookingService booking;
    @Autowired
    BookingJournal journal;
    @Autowired
    ClientMergeService merge;
    @Autowired
    Clock clock;

    private final ObjectMapper mapper = new ObjectMapper();

    private UUID tenant;
    private ZoneId zone;
    private UUID m1;
    private UUID m2;
    private UUID m3;
    private UUID cut;
    private UUID consult;
    private UUID color;
    private int phoneSeq;

    @AfterEach
    void clearContexts() {
        ActorContext.clear();
        TenantContext.clear();
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    /** A zone (no DST) in which {@code now} is >= 20 h after its month start and >= 2 h before the next month. */
    static ZoneId zoneFor(Instant now) {
        for (String id : List.of("Etc/UTC", "Pacific/Pago_Pago", "Pacific/Kiritimati")) {
            ZoneId z = ZoneId.of(id);
            LocalDateTime local = LocalDateTime.ofInstant(now, z);
            LocalDateTime monthStart = local.toLocalDate().withDayOfMonth(1).atStartOfDay();
            if (Duration.between(monthStart, local).toHours() >= 20
                    && Duration.between(local, monthStart.plusMonths(1)).toHours() >= 2) {
                return z;
            }
        }
        throw new IllegalStateException("no zone fits " + now);
    }

    // ---------------------------------------------------------------- setup

    private <T> T asSystem(Supplier<T> call) {
        try (var ignored = TenantContext.open(tenant)) {
            ActorContext.set(ActorContext.system());
            return call.get();
        } finally {
            ActorContext.clear();
        }
    }

    private <T> T asOwner(Supplier<T> call) {
        try (var ignored = TenantContext.open(tenant)) {
            ActorContext.set(new ActorContext.Actor("owner-" + tenant, ActorContext.Role.OWNER, null));
            return call.get();
        } finally {
            ActorContext.clear();
        }
    }

    private void setUpTenant() {
        tenant = UUID.randomUUID();
        zone = zoneFor(clock.instant());
        tenants.upsert(tenant, "replay-" + tenant, "Replay Studio", zone.getId(), "EUR", "CZ");
        asSystem(() -> {
            catalog.saveRules(new BookingRulesView(15, 60, 30, true, 2, false, false, 1, true));
            cut = catalog.upsertService(new ServiceView(null, "Cut", "Cut", 30, 50000, 0, 0, "#0369a1", true, true)).id();
            consult = catalog.upsertService(new ServiceView(null, "Consult", "Consult", 15, 0, 0, 0, "#57534e", true, true)).id();
            color = catalog.upsertService(new ServiceView(null, "Color", "Color", 60, 150000, 0, 0, "#7c3aed", true, true)).id();
            m1 = catalog.upsertSpecialist(new SpecialistView(null, "Master One", null, "#0f766e", true)).id();
            m2 = catalog.upsertSpecialist(new SpecialistView(null, "Master Two", null, "#7c3aed", true)).id();
            m3 = catalog.upsertSpecialist(new SpecialistView(null, "Master Three", null, "#be123c", true)).id();
            for (UUID m : List.of(m1, m2, m3)) {
                for (UUID s : List.of(cut, consult, color)) {
                    catalog.upsertMatrix(new MasterServiceView(UUID.randomUUID(), m, s, true, null, null, null, null));
                }
            }
            List<WeeklyInterval> allDay = new ArrayList<>();
            List<WeeklyInterval> office = new ArrayList<>();
            for (int d = 1; d <= 7; d++) {
                allDay.add(new WeeklyInterval(UUID.randomUUID(), m1, d, 0, 1440));
                if (d <= 5) {
                    office.add(new WeeklyInterval(UUID.randomUUID(), m2, d, 540, 1020));
                }
            }
            catalog.replaceWeeklySchedule(m1, allDay);
            catalog.replaceWeeklySchedule(m2, office);
            catalog.replaceWeeklySchedule(m3, List.of(new WeeklyInterval(UUID.randomUUID(), m3, 1, 600, 1200)));
            // Fixed dates, independent of the time of day (review M2): several exceptions on one date are resolved by
            // the rule of WorkingHours.appliedException, mirrored by the replay SQL.
            LocalDate monthStart = LocalDate.ofInstant(clock.instant(), zone).withDayOfMonth(1);
            LocalDate previous = monthStart.minusMonths(1);
            exception(m2, previous.withDayOfMonth(3), "TRAINING", null, null);
            exception(m2, previous.withDayOfMonth(3), "DAY_OFF", null, null);
            exception(m2, previous.withDayOfMonth(3), "SHIFT", 600, 720);
            exception(m2, previous.withDayOfMonth(4), "SHIFT", 660, 900);
            exception(m2, previous.withDayOfMonth(4), "SHIFT", 600, 960);
            exception(m2, previous.withDayOfMonth(4), "SHIFT", null, 700);
            exception(m2, monthStart, "DAY_OFF", null, null);
            exception(m1, monthStart, "TRAINING", null, null);
            exception(m1, monthStart, "SHIFT", 0, 600);
            return null;
        });
    }

    private void exception(UUID master, LocalDate date, String kind, Integer start, Integer end) {
        catalog.upsertException(new ScheduleExceptionView(null, master, date, kind, start, end, kind));
    }

    private String nextPhone() {
        return String.format("+42077710%04d", ++phoneSeq);
    }

    /** Owner console create; past visits skip notice/horizon, the status is as given. */
    private AppointmentView create(UUID master, UUID service, Instant start, UUID clientId, AppointmentStatus status) {
        String phone = clientId == null ? nextPhone() : null;
        return asOwner(() -> booking.create(new CreateBookingCommand(master, service, start, clientId, phone,
                clientId == null ? "Client " + phone : "Client", "en", false, "replay", null, "CONSOLE", status, true)));
    }

    private AppointmentView transition(AppointmentView visit, AppointmentStatus to) {
        return asOwner(() -> booking.transition(visit.id(), to, null));
    }

    // ---------------------------------------------------------------- journal checks

    private long events(UUID appointmentId) throws SQLException {
        try (Connection c = ownerConnection();
             PreparedStatement st = c.prepareStatement("select count(*) from appointment_event where appointment_id = ?")) {
            st.setObject(1, appointmentId);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private interface Write {
        void run() throws Exception;
    }

    /** Runs {@code write} and requires exactly {@code expected} new events on each of {@code visits}. */
    private void expectEvents(long expected, Write write, UUID... visits) throws Exception {
        Map<UUID, Long> before = new HashMap<>();
        for (UUID v : visits) {
            before.put(v, events(v));
        }
        write.run();
        for (UUID v : visits) {
            assertThat(events(v) - before.get(v)).as("new journal events of visit " + v).isEqualTo(expected);
        }
    }

    /** Past slot {@code i}: now - 1 h - 40 min * i, on the 5-minute grid; i <= 25 stays within the last 18 hours. */
    private Instant pastSlot(int i) {
        return clock.instant().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1)).minus(Duration.ofMinutes(40L * i));
    }

    private Instant previousMonth(int day, int hour) {
        LocalDate first = LocalDate.ofInstant(clock.instant(), zone).withDayOfMonth(1).minusMonths(1);
        return first.withDayOfMonth(day).atTime(hour, 0).atZone(zone).toInstant();
    }

    private Instant monthsAgo(int months, int day) {
        LocalDate first = LocalDate.ofInstant(clock.instant(), zone).withDayOfMonth(1).minusMonths(months);
        return first.withDayOfMonth(day).atTime(10, 0).atZone(zone).toInstant();
    }

    private Instant freeSlot(UUID master, UUID service, int dayOffset, int index) {
        LocalDate day = LocalDate.ofInstant(clock.instant(), zone).plusDays(dayOffset);
        List<Instant> free = asOwner(() -> booking.freeSlots(master, service, day, day));
        assertThat(free).as("free slots on " + day).hasSizeGreaterThan(index);
        return free.get(index);
    }

    // ---------------------------------------------------------------- the scenario

    @Test
    void everyWritePathIsJournaledAndTheSqlReplayEqualsTheSnapshots() throws Exception {
        setUpTenant();
        List<UUID> visits = new ArrayList<>();

        // History: clients with completed visits in earlier months.
        AppointmentView a1 = create(m1, cut, previousMonth(5, 10), null, AppointmentStatus.COMPLETED);
        UUID clientA = a1.clientId();
        AppointmentView b1 = create(m1, cut, previousMonth(8, 10), null, AppointmentStatus.COMPLETED);
        UUID clientB = b1.clientId();
        AppointmentView c0 = create(m2, cut, monthsAgo(2, 12), null, AppointmentStatus.COMPLETED);
        UUID clientC = c0.clientId();
        AppointmentView c1 = create(m2, cut, previousMonth(12, 10), clientC, AppointmentStatus.COMPLETED);
        AppointmentView k0 = create(m1, color, previousMonth(15, 10), null, AppointmentStatus.COMPLETED);
        UUID clientK = k0.clientId();
        AppointmentView prevNoShow = create(m2, cut, previousMonth(20, 10), clientB, AppointmentStatus.NO_SHOW);
        visits.addAll(List.of(a1.id(), b1.id(), c0.id(), c1.id(), k0.id(), prevNoShow.id()));

        // Current month: console creates in a settled status (1 event each).
        List<AppointmentView> created = new ArrayList<>();
        expectEvents(1, () -> created.add(create(m1, cut, pastSlot(0), clientA, AppointmentStatus.COMPLETED)));
        created.add(create(m1, cut, pastSlot(1), clientB, AppointmentStatus.COMPLETED));
        created.add(create(m2, cut, pastSlot(2), clientC, AppointmentStatus.COMPLETED));
        AppointmentView d1 = create(m1, cut, pastSlot(3), null, AppointmentStatus.COMPLETED);
        created.add(d1);
        created.add(create(m1, cut, pastSlot(4), d1.clientId(), AppointmentStatus.COMPLETED));
        created.add(create(m1, consult, pastSlot(5), null, AppointmentStatus.COMPLETED));
        created.add(create(m1, consult, pastSlot(6), null, AppointmentStatus.COMPLETED));
        created.add(create(m1, consult, pastSlot(7), null, AppointmentStatus.COMPLETED));
        created.add(create(m2, color, pastSlot(8), null, AppointmentStatus.COMPLETED));
        created.add(create(m2, cut, pastSlot(9), null, AppointmentStatus.COMPLETED));
        created.add(create(m3, cut, pastSlot(10), null, AppointmentStatus.COMPLETED));
        AppointmentView k1 = create(m1, cut, pastSlot(11), clientK, AppointmentStatus.COMPLETED);
        created.add(k1);
        AppointmentView s20 = create(m2, cut, pastSlot(20), null, AppointmentStatus.NO_SHOW);
        AppointmentView s21 = create(m2, cut, pastSlot(21), null, AppointmentStatus.NO_SHOW);
        created.add(s20);
        created.add(s21);
        created.forEach(v -> visits.add(v.id()));

        // Transitions: one event per change, corrections included.
        AppointmentView l = create(m1, cut, pastSlot(12), null, AppointmentStatus.CONFIRMED);
        expectEvents(1, () -> transition(l, AppointmentStatus.NO_SHOW), l.id());
        AppointmentView m = create(m1, cut, pastSlot(13), null, AppointmentStatus.CONFIRMED);
        expectEvents(1, () -> transition(m, AppointmentStatus.COMPLETED), m.id());
        expectEvents(1, () -> transition(m, AppointmentStatus.NO_SHOW), m.id());
        AppointmentView n = create(m1, cut, pastSlot(14), null, AppointmentStatus.CONFIRMED);
        transition(n, AppointmentStatus.NO_SHOW);
        expectEvents(1, () -> transition(n, AppointmentStatus.COMPLETED), n.id());
        AppointmentView o = create(m2, cut, pastSlot(15), null, AppointmentStatus.CONFIRMED);
        AppointmentView p = create(m2, cut, pastSlot(16), null, AppointmentStatus.PENDING);
        AppointmentView q = create(m1, cut, pastSlot(17), null, AppointmentStatus.CONFIRMED);
        expectEvents(1, () -> transition(q, AppointmentStatus.CANCELLED_BY_BUSINESS), q.id());
        AppointmentView p2 = create(m2, cut, pastSlot(19), null, AppointmentStatus.PENDING);
        expectEvents(1, () -> transition(p2, AppointmentStatus.CONFIRMED), p2.id());
        expectEvents(1, () -> transition(p2, AppointmentStatus.COMPLETED), p2.id());
        visits.addAll(List.of(l.id(), m.id(), n.id(), o.id(), p.id(), q.id(), p2.id()));

        // Console reschedule of a future visit.
        AppointmentView s = create(m1, cut, freeSlot(m1, cut, 1, 0), null, AppointmentStatus.CONFIRMED);
        Instant moveTo = freeSlot(m1, cut, 1, 4);
        expectEvents(1, () -> asOwner(() -> booking.reschedule(s.id(), moveTo)), s.id());
        visits.add(s.id());

        // Public booking, reschedule and cancel by token; consent withdrawal and an access link write no event.
        Instant publicStart = freeSlot(m1, cut, 2, 0);
        PublicBookingResult[] pub = new PublicBookingResult[1];
        String publicPhone = nextPhone();
        try (var ignored = TenantContext.open(tenant)) {
            pub[0] = booking.createPublic(new CreateBookingCommand(m1, cut, publicStart, null, publicPhone, "Walk In",
                    "en", true, "public-v1", null, "PUBLIC", null, false));
        }
        UUID publicVisit = pub[0].appointment().id();
        String token = pub[0].accessToken();
        assertThat(events(publicVisit)).isEqualTo(1);
        Instant publicMove = freeSlot(m1, cut, 2, 6);
        expectEvents(1, () -> booking.rescheduleByAccessToken(token, publicMove), publicVisit);
        expectEvents(0, () -> booking.withdrawConsentByAccessToken(publicVisit, token), publicVisit);
        expectEvents(0, () -> asOwner(() -> booking.issueAccessLink(o.id())), o.id());
        expectEvents(1, () -> booking.cancelByAccessToken(token, true), publicVisit);
        visits.add(publicVisit);

        // Merge K into A: one CLIENT_CHANGED per moved visit.
        expectEvents(1, () -> asOwner(() -> merge.merge(clientA, clientK)), k0.id(), k1.id());

        // M3 leaves: its visits stay facts, it is no longer in S of SLOT_UTILIZATION and has no slice.
        asSystem(() -> catalog.upsertSpecialist(new SpecialistView(m3, "Master Three", null, "#be123c", false)));

        // ---- first recalculation through the API ----
        // Current month: D = 20 due visits, N = 4 no-shows (l, m, s20, s21) -> 0.20, NO_SHOW_SPIKE fires.
        Instant firstAsOf = recalculate(() -> { });
        assertReplayEqualsSnapshots(firstAsOf);
        assertThat(asOwner(() -> journal.reconcile(clock.instant()))).as("journal vs rows").isEmpty();
        assertThat(mismatchAudits()).isZero();

        Map<String, UUID> signals = signalIds();
        assertThat(signals.keySet()).containsExactlyInAnyOrder("NO_SHOW_SPIKE", "UTILIZATION_GAP", "LOW_VALUE_SERVICES");
        JsonNode lowValue = signalByKey("LOW_VALUE_SERVICES");
        assertThat(lowValue).isNotNull();
        assertThat(lowValue.get("evidence").asText()).contains("Consult earns 0%").doesNotContain("EUR");
        assertThat(openSignalKeys()).containsExactlyInAnyOrder("NO_SHOW_SPIKE", "UTILIZATION_GAP", "LOW_VALUE_SERVICES");

        // A late correction after the first snapshot: the next snapshot changes, the replay of the old one does not.
        expectEvents(1, () -> transition(o, AppointmentStatus.NO_SHOW), o.id());

        // ---- second recalculation, with an event stamped inside the as-of lag (review M3) ----
        Instant[] insideLag = new Instant[1];
        Instant secondAsOf = recalculate(() -> {
            transition(p, AppointmentStatus.CONFIRMED);
            insideLag[0] = lastEventAt(p.id());
        });
        assertThat(secondAsOf).isAfter(firstAsOf);
        assertThat(insideLag[0]).as("the event written just before the recalculation is after its as-of").isAfter(secondAsOf);
        assertThat(asOwner(() -> journal.fold(secondAsOf)).facts().stream()
                .filter(f -> f.appointmentId().equals(p.id())).findFirst().orElseThrow().status())
                .as("fold at the snapshot's as-of leaves the inside-lag event out").isEqualTo(AppointmentStatus.PENDING);
        assertThat(signalIds()).isEqualTo(signals);
        assertReplayEqualsSnapshots(secondAsOf);
        assertReplayEqualsSnapshots(firstAsOf);
        assertThat(snapshotValue(firstAsOf, "NO_SHOW_RATE")).isNotEqualTo(snapshotValue(secondAsOf, "NO_SHOW_RATE"));

        // ---- the rule stops firing: the period's signal resolves itself (review m4) ----
        // l, s20, s21 were completed after all: N = 2 (m, o) of 20 -> 0.10 < 0.15.
        for (AppointmentView fixed : List.of(l, s20, s21)) {
            expectEvents(1, () -> transition(fixed, AppointmentStatus.COMPLETED), fixed.id());
        }
        Instant thirdAsOf = recalculate(() -> { });
        assertReplayEqualsSnapshots(thirdAsOf);
        assertThat(signalIds()).isEqualTo(signals);
        assertThat(resolvedAt(signals.get("NO_SHOW_SPIKE"))).isNotNull();
        assertThat(resolvedAt(signals.get("UTILIZATION_GAP"))).isNull();
        assertThat(openSignalKeys()).containsExactlyInAnyOrder("UTILIZATION_GAP", "LOW_VALUE_SERVICES");

        // ---- it fires again later in the period: re-opened, same row ----
        for (AppointmentView back : List.of(s20, s21)) {
            expectEvents(1, () -> transition(back, AppointmentStatus.NO_SHOW), back.id());
        }
        recalculate(() -> { });
        assertThat(signalIds()).isEqualTo(signals);
        assertThat(resolvedAt(signals.get("NO_SHOW_SPIKE"))).isNull();
        assertThat(openSignalKeys()).containsExactlyInAnyOrder("NO_SHOW_SPIKE", "UTILIZATION_GAP", "LOW_VALUE_SERVICES");

        // ---- concurrent recalculations and a dismiss (review m1): all succeed, nothing duplicated or revived ----
        UUID dismissed = signals.get("NO_SHOW_SPIKE");
        waitForLag();
        List<Integer> statuses = concurrently(List.of(
                () -> postStatus("/api/app/metrics/recalculate"),
                () -> postStatus("/api/app/metrics/recalculate"),
                () -> postStatus("/api/app/metrics/recalculate"),
                () -> postStatus("/api/app/signals/" + dismissed + "/dismiss"),
                () -> postStatus("/api/app/metrics/recalculate")));
        assertThat(statuses).containsOnly(200);
        assertThat(signalIds()).isEqualTo(signals);
        assertThat(dismissedAt(dismissed)).isNotNull();
        assertThat(openSignalKeys()).containsExactlyInAnyOrder("UTILIZATION_GAP", "LOW_VALUE_SERVICES");

        // ---- a dismissed signal is not revived, neither by a recalculation nor by resolving and firing again ----
        recalculate(() -> { });
        assertThat(dismissedAt(dismissed)).isNotNull();
        assertThat(resolvedAt(dismissed)).isNull();
        assertThat(signalsJson().findValues("id").stream().map(JsonNode::asText)).doesNotContain(dismissed.toString());

        // The dashboard reads the current window (month to date) with the delta against the previous month only.
        JsonNode metrics = mapper.readTree(mvc.perform(get("/api/app/metrics").with(owner(tenant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        for (JsonNode metric : metrics) {
            assertThat(metric.get("windowStart").asText()).isEqualTo(today.withDayOfMonth(1).toString());
        }

        // ---- a row changed behind the journal is audited ----
        try (Connection c = ownerConnection();
             PreparedStatement st = c.prepareStatement("update appointment set price_snapshot = price_snapshot + 1 where id = ?")) {
            st.setObject(1, a1.id());
            assertThat(st.executeUpdate()).isEqualTo(1);
        }
        List<JournalDefect> defects = asOwner(() -> journal.reconcile(clock.instant()));
        assertThat(defects).singleElement().satisfies(d -> {
            assertThat(d.appointmentId()).isEqualTo(a1.id());
            assertThat(d.kind()).isEqualTo(JournalDefect.ROW_MISMATCH);
            assertThat(d.detail()).contains("priceMinor");
        });
        recalculate(() -> { });
        assertThat(mismatchAudits()).isEqualTo(1);
        assertThat(visits).doesNotHaveDuplicates();
    }

    /** A MASTER never reads the journal. */
    @Test
    void theJournalPortRefusesAMaster() {
        setUpTenant();
        try (var ignored = TenantContext.open(tenant)) {
            ActorContext.set(new ActorContext.Actor("master", ActorContext.Role.MASTER, m1));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> journal.fold(clock.instant()))
                    .hasMessageContaining("Only the owner or the system");
        }
    }

    /**
     * Completeness guard: every public method of {@link BookingService} is classified, and every mutating one is
     * exercised above with its exact event count. A new method fails here until it is classified (and journaled).
     */
    @Test
    void everyBookingServiceMethodIsClassifiedForTheJournal() {
        Set<String> mutating = Set.of("create", "createPublic", "transition", "reschedule", "cancelByAccessToken",
                "rescheduleByAccessToken", "reassignClient");
        Set<String> noFactChange = Set.of("moduleName", "freeSlots", "calendar", "find", "clientIdsOf", "clientHistory",
                "findByAccessToken", "withdrawConsentByAccessToken", "ics", "icsByAccessToken", "issueAccessLink");
        Set<String> declared = new TreeSet<>();
        Arrays.stream(BookingService.class.getMethods()).map(Method::getName).forEach(declared::add);
        Set<String> classified = new TreeSet<>(mutating);
        classified.addAll(noFactChange);
        assertThat(declared).isEqualTo(classified);
    }

    // ---------------------------------------------------------------- recalculation helpers

    /** {@code app.metrics.as-of-lag} of the IT context (AbstractTimescaleIT). */
    static final Duration AS_OF_LAG = Duration.ofSeconds(2);

    /** Waits until everything written so far is older than now minus the lag. */
    private static void waitForLag() throws InterruptedException {
        Thread.sleep(AS_OF_LAG.toMillis() + 300);
    }

    /**
     * POST /metrics/recalculate after the lag, with {@code justBefore} written right before it; returns the snapshot's
     * as-of and checks it is now minus the lag (review M3).
     */
    private Instant recalculate(Write justBefore) throws Exception {
        waitForLag();
        justBefore.run();
        Instant before = clock.instant();
        mvc.perform(post("/api/app/metrics/recalculate").with(owner(tenant))).andExpect(status().isOk());
        Instant after = clock.instant();
        Instant asOf = latestSnapshotAt();
        assertThat(asOf).as("as-of = now - lag").isBetween(before.minus(AS_OF_LAG).minusMillis(1), after.minus(AS_OF_LAG));
        return asOf;
    }

    private int postStatus(String path) throws Exception {
        return mvc.perform(post(path).with(owner(tenant))).andReturn().getResponse().getStatus();
    }

    private interface Call {
        int run() throws Exception;
    }

    /** Runs the calls on their own threads, released together; returns their HTTP statuses. */
    private static List<Integer> concurrently(List<Call> calls) throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(calls.size());
        try {
            List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
            for (Call call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.run();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var f : futures) {
                statuses.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private Instant lastEventAt(UUID appointmentId) throws SQLException {
        try (Connection c = ownerConnection();
             PreparedStatement st = c.prepareStatement("select max(created_at) from appointment_event where appointment_id = ?")) {
            st.setObject(1, appointmentId);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
            }
        }
    }

    // ---------------------------------------------------------------- replay

    private Instant latestSnapshotAt() throws SQLException {
        try (Connection c = ownerConnection();
             PreparedStatement st = c.prepareStatement("select max(created_at) from metric_snapshot where tenant_id = ?")) {
            st.setObject(1, tenant);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
            }
        }
    }

    private void assertReplayEqualsSnapshots(Instant asOf) throws Exception {
        MetricsReplay.Outcome outcome = MetricsReplay.assertEqualsSnapshots(MetricsJournalReplayIT::ownerConnection, tenant, asOf);
        // 2 windows x 6 metrics x (tenant + 2 active masters).
        assertThat(outcome.compared()).isEqualTo(36);
        assertThat(outcome.withValue()).as("metrics with enough data in the comparison").isGreaterThanOrEqualTo(10);
    }

    private Double snapshotValue(Instant asOf, String key) throws SQLException {
        try (Connection c = ownerConnection();
             PreparedStatement st = c.prepareStatement("""
                     select value_numeric from metric_snapshot
                     where tenant_id = ? and created_at = ? and metric_key = ? and specialist_id is null
                     order by window_start desc limit 1""")) {
            st.setObject(1, tenant);
            st.setTimestamp(2, Timestamp.valueOf(LocalDateTime.ofInstant(asOf, java.time.ZoneOffset.UTC)));
            st.setString(3, key);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                double v = rs.getDouble(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    // ---------------------------------------------------------------- signals and audit

    private Map<String, UUID> signalIds() throws SQLException {
        Map<String, UUID> ids = new HashMap<>();
        try (Connection c = ownerConnection();
             PreparedStatement st = c.prepareStatement("select signal_key, id from growth_signal where tenant_id = ?")) {
            st.setObject(1, tenant);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    assertThat(ids.put(rs.getString(1), rs.getObject(2, UUID.class))).as("one row per signal key").isNull();
                }
            }
        }
        return ids;
    }

    private Timestamp dismissedAt(UUID id) throws SQLException {
        try (Connection c = ownerConnection();
             PreparedStatement st = c.prepareStatement("select dismissed_at from growth_signal where id = ?")) {
            st.setObject(1, id);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1);
            }
        }
    }

    private Timestamp resolvedAt(UUID id) throws SQLException {
        try (Connection c = ownerConnection();
             PreparedStatement st = c.prepareStatement("select resolved_at from growth_signal where id = ?")) {
            st.setObject(1, id);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1);
            }
        }
    }

    /** Keys of GET /signals (current period, not resolved, not dismissed). */
    private List<String> openSignalKeys() throws Exception {
        return signalsJson().findValues("key").stream().map(JsonNode::asText).toList();
    }

    private JsonNode signalsJson() throws Exception {
        return mapper.readTree(mvc.perform(get("/api/app/signals").with(owner(tenant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode signalByKey(String key) throws Exception {
        for (JsonNode s : signalsJson()) {
            if (key.equals(s.get("key").asText())) {
                return s;
            }
        }
        return null;
    }

    private long mismatchAudits() throws SQLException {
        try (Connection c = ownerConnection();
             PreparedStatement st = c.prepareStatement(
                     "select count(*) from audit_log where tenant_id = ? and action = 'growth.journal_mismatch'")) {
            st.setObject(1, tenant);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
