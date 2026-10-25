package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentEventType;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A losing writer in the double-booking race gets the exclusion violation from PostgreSQL; the repository must turn
 * it into {@code SLOT_OCCUPIED} (409) and leave every other failure alone. The database is a jOOQ mock connection
 * that fails the statement with a given SQLSTATE, with and without Boot's Spring exception translation in front.
 */
class AppointmentRepositorySlotConflictTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-09-01T10:00:00Z");

    @BeforeEach
    void tenant() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static AppointmentRepository repositoryFailingWith(SQLException failure, boolean springTranslation) {
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            throw failure;
        }), SQLDialect.POSTGRES);
        if (springTranslation) {
            // What Boot's jOOQ auto-configuration installs: SQLErrorCodeSQLExceptionTranslator for PostgreSQL.
            var translator = new SQLErrorCodeSQLExceptionTranslator("PostgreSQL");
            dsl.configuration().set(new DefaultExecuteListenerProvider(new DefaultExecuteListener() {
                @Override
                public void exception(ExecuteContext ctx) {
                    ctx.exception(translator.translate("jOOQ", ctx.sql(), ctx.sqlException()));
                }
            }));
        }
        return new AppointmentRepository(dsl, Clock.systemUTC());
    }

    private static SQLException exclusion() {
        return new SQLException(
                "ERROR: conflicting key value violates exclusion constraint \"" + AppointmentRepository.NO_OVERLAP_CONSTRAINT + "\"",
                "23P01");
    }

    private static AppointmentView visit() {
        return new AppointmentView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AppointmentStatus.CONFIRMED, START, START.plusSeconds(2700), START.minusSeconds(300), START.plusSeconds(3300),
                "Haircut", 8000, 45, 5, 10, "GEL", 0, null, "Mariam", null, "PUBLIC", "Mariam", null, null, false);
    }

    /** The CREATED write path (row insert plus journal event). */
    private static void create(AppointmentRepository repository) {
        AppointmentView visit = visit();
        repository.record(visit, AppointmentEventType.CREATED, JournalPayloads.Created.of(visit));
    }

    /** A move: the status-guarded update expects the visit's unchanged status (CONFIRMED). */
    private static void move(AppointmentRepository repository) {
        repository.record(visit(), AppointmentEventType.RESCHEDULED, new JournalPayloads.Rescheduled(START, START));
    }

    private static void assertSlotOccupied(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.code()).isEqualTo("SLOT_OCCUPIED");
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.getMessage()).doesNotContain("constraint").doesNotContain("SQL");
        });
    }

    @Test
    void insertExclusionViolationBecomesSlotOccupied() {
        for (boolean spring : new boolean[]{false, true}) {
            var repository = repositoryFailingWith(exclusion(), spring);
            assertThatThrownBy(() -> create(repository)).satisfies(AppointmentRepositorySlotConflictTest::assertSlotOccupied);
        }
    }

    @Test
    void rescheduleUpdateExclusionViolationBecomesSlotOccupied() {
        for (boolean spring : new boolean[]{false, true}) {
            var repository = repositoryFailingWith(exclusion(), spring);
            assertThatThrownBy(() -> move(repository)).satisfies(AppointmentRepositorySlotConflictTest::assertSlotOccupied);
        }
    }

    @Test
    void statusGuardedUpdateThatMatchesNoRowIsAConcurrentModificationConflict() {
        DSLContext dsl = DSL.using(new MockConnection(ctx -> new MockResult[]{new MockResult(0)}), SQLDialect.POSTGRES);
        var repository = new AppointmentRepository(dsl, Clock.systemUTC());

        assertThatThrownBy(() -> move(repository))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("CONCURRENT_MODIFICATION");
                    assertThat(e.status()).isEqualTo(409);
                });
    }

    @Test
    void statusGuardedUpdateThatMatchesTheRowSucceedsAndFiltersOnTheExpectedStatus() {
        var sql = new java.util.ArrayList<String>();
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            sql.add(ctx.sql());
            if (ctx.sql().toLowerCase().startsWith("select")) {
                // No earlier event of the visit (the time floor of review m2).
                return new MockResult[]{new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(
                        DSL.field("max", org.jooq.impl.SQLDataType.LOCALDATETIME)))};
            }
            return new MockResult[]{new MockResult(1)};
        }), SQLDialect.POSTGRES);
        var repository = new AppointmentRepository(dsl, Clock.systemUTC());

        move(repository);

        // The guarded row update first, then the visit's last event time, then the journal event in the same write.
        assertThat(sql).hasSize(3);
        assertThat(sql.get(0)).startsWithIgnoringCase("update").contains("\"status\" = ?").containsIgnoringCase("where");
        assertThat(sql.get(1)).startsWithIgnoringCase("select").contains("appointment_event");
        assertThat(sql.get(2)).startsWithIgnoringCase("insert").contains("appointment_event");
    }

    @Test
    void findForUpdateTakesTheRowLock() {
        var sql = new java.util.ArrayList<String>();
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            sql.add(ctx.sql());
            return new MockResult[]{new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult())};
        }), SQLDialect.POSTGRES);

        assertThat(new AppointmentRepository(dsl, Clock.systemUTC()).findForUpdate(UUID.randomUUID())).isEmpty();

        assertThat(sql).singleElement().asString().containsIgnoringCase("for update");
    }

    @Test
    void originalFailureIsKeptAsCauseForDiagnostics() {
        var repository = repositoryFailingWith(exclusion(), true);

        assertThatThrownBy(() -> create(repository))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasRootCauseMessage(exclusion().getMessage());
    }

    @Test
    void otherIntegrityFailuresAreNotMasqueradedAsSlotConflicts() {
        var foreignKey = new SQLException("ERROR: insert violates foreign key constraint \"appointment_client_fk\"", "23503");
        for (boolean spring : new boolean[]{false, true}) {
            var repository = repositoryFailingWith(foreignKey, spring);
            assertThatThrownBy(() -> create(repository)).isNotInstanceOf(DomainException.class);
            assertThatThrownBy(() -> move(repository)).isNotInstanceOf(DomainException.class);
        }
    }

    @Test
    void exclusionViolationOfAnotherConstraintIsNotASlotConflict() {
        var other = new SQLException("ERROR: conflicting key value violates exclusion constraint \"some_other_ex\"", "23P01");
        var repository = repositoryFailingWith(other, true);

        assertThatThrownBy(() -> create(repository)).isNotInstanceOf(DomainException.class);
    }
}
