package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentEventType;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.platform.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review m2: the order of a visit's events is (created_at, id), so a later event must never get an earlier time,
 * even when the clock steps back (NTP). {@code record} stamps every non-CREATED event with
 * max(clock, previous event of the visit + 1 µs); the visit row is locked by then.
 */
class AppointmentRepositoryJournalTimeTest {

    private static final Instant START = Instant.parse("2026-09-01T10:00:00Z");
    private static final LocalDateTime LAST_EVENT = LocalDateTime.parse("2026-08-30T12:00:00.000500");

    private final List<String> sql = new ArrayList<>();
    private final List<Object[]> bindings = new ArrayList<>();

    @BeforeEach
    void tenant() {
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** The database: the previous event of the visit is at {@code last} (or none); writes touch one row. */
    private AppointmentRepository repository(Instant clockAt, LocalDateTime last) {
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            sql.add(ctx.sql());
            bindings.add(ctx.bindings());
            if (ctx.sql().toLowerCase().startsWith("select")) {
                var max = DSL.field("max", SQLDataType.LOCALDATETIME);
                Result<Record1<LocalDateTime>> result = DSL.using(SQLDialect.POSTGRES).newResult(max);
                result.add(DSL.using(SQLDialect.POSTGRES).newRecord(max).values(last));
                return new MockResult[]{new MockResult(1, result)};
            }
            return new MockResult[]{new MockResult(1)};
        }), SQLDialect.POSTGRES);
        return new AppointmentRepository(dsl, Clock.fixed(clockAt, ZoneOffset.UTC));
    }

    private static AppointmentView visit() {
        return new AppointmentView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AppointmentStatus.CONFIRMED, START, START.plusSeconds(2700), START, START.plusSeconds(2700),
                "Haircut", 8000, 45, 0, 0, "GEL", 0, null, "Mariam", null, "CONSOLE", "Mariam", null, null, false);
    }

    /** The created_at bound into the journal insert. */
    private LocalDateTime eventTime() {
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).toLowerCase().startsWith("insert") && sql.get(i).contains("appointment_event")) {
                // The last bind value of the insert is created_at (a LocalDateTime or, depending on the binding, a Timestamp).
                Object value = bindings.get(i)[bindings.get(i).length - 1];
                return value instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) value;
            }
        }
        throw new AssertionError("no journal insert in " + sql);
    }

    @Test
    void aClockBehindThePreviousEventStampsOneMicrosecondAfterIt() {
        Instant behind = LAST_EVENT.toInstant(ZoneOffset.UTC).minusSeconds(3600);
        repository(behind, LAST_EVENT).record(visit(), AppointmentEventType.COMPLETED,
                new JournalPayloads.StatusChange(AppointmentStatus.CONFIRMED, AppointmentStatus.COMPLETED, false));

        assertThat(eventTime()).isEqualTo(LAST_EVENT.plusNanos(1_000));
    }

    @Test
    void aClockAheadOfThePreviousEventIsUsedAsIs() {
        Instant ahead = Instant.parse("2026-09-02T08:00:00.123456Z");
        repository(ahead, LAST_EVENT).record(visit(), AppointmentEventType.RESCHEDULED,
                new JournalPayloads.Rescheduled(START, START.plusSeconds(3600)));

        assertThat(eventTime()).isEqualTo(LocalDateTime.ofInstant(ahead, ZoneOffset.UTC));
    }

    @Test
    void createdUsesTheClockAndReadsNoPreviousEvent() {
        Instant at = Instant.parse("2026-08-01T08:00:00Z");
        AppointmentView v = visit();
        repository(at, LAST_EVENT).record(v, AppointmentEventType.CREATED, JournalPayloads.Created.of(v));

        assertThat(eventTime()).isEqualTo(LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        assertThat(sql).noneMatch(s -> s.toLowerCase().startsWith("select"));
    }
}
