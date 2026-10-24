package com.cadence.platform.persistence;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatesTest {

    private static final String SLOT = "no_overlapping_active_appointments";
    private static final String PHONE = "client_phone_uq";

    private static SQLException exclusion() {
        return new SQLException(
                "ERROR: conflicting key value violates exclusion constraint \"" + SLOT + "\"\n  Detail: ...",
                "23P01"
        );
    }

    private static SQLException unique() {
        return new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + PHONE + "\"",
                "23505"
        );
    }

    @Test
    void recognisesExclusionViolationThroughSpringWrapper() {
        var wrapped = new DataIntegrityViolationException("jOOQ; SQL [insert ...]", exclusion());

        assertThat(SqlStates.isExclusionViolation(wrapped, SLOT)).isTrue();
        assertThat(SqlStates.isExclusionViolation(wrapped, null)).isTrue();
        assertThat(SqlStates.isUniqueViolation(wrapped, null)).isFalse();
    }

    @Test
    void recognisesUniqueViolationThroughJooqWrapper() {
        var wrapped = new DataAccessException("SQL [insert ...]; ERROR", unique());

        assertThat(SqlStates.isUniqueViolation(wrapped, PHONE)).isTrue();
        assertThat(SqlStates.isExclusionViolation(wrapped, null)).isFalse();
    }

    @Test
    void recognisesViolationDeepInCauseChain() {
        var chain = new RuntimeException("outer", new IllegalStateException("middle", new RuntimeException("inner", exclusion())));

        assertThat(SqlStates.isExclusionViolation(chain, SLOT)).isTrue();
    }

    @Test
    void recognisesViolationInNextExceptionChain() {
        SQLException head = new SQLException("batch failed", "08000");
        head.setNextException(exclusion());

        assertThat(SqlStates.isExclusionViolation(new RuntimeException(head), SLOT)).isTrue();
    }

    @Test
    void otherConstraintOfSameStateIsNotMatched() {
        var otherExclusion = new SQLException("ERROR: conflicting key value violates exclusion constraint \"other_ex\"", "23P01");
        var otherUnique = new SQLException("ERROR: duplicate key value violates unique constraint \"client_email_uq\"", "23505");

        assertThat(SqlStates.isExclusionViolation(new RuntimeException(otherExclusion), SLOT)).isFalse();
        assertThat(SqlStates.isUniqueViolation(new RuntimeException(otherUnique), PHONE)).isFalse();
    }

    @Test
    void otherSqlStatesAreNotMatched() {
        var foreignKey = new SQLException("ERROR: violates foreign key constraint \"" + SLOT + "\"", "23503");
        var noState = new SQLException("connection reset");

        assertThat(SqlStates.isExclusionViolation(new RuntimeException(foreignKey), SLOT)).isFalse();
        assertThat(SqlStates.isUniqueViolation(new RuntimeException(noState), null)).isFalse();
    }

    @Test
    void failureWithoutSqlExceptionIsNotMatched() {
        assertThat(SqlStates.isExclusionViolation(new IllegalStateException("boom"), null)).isFalse();
        assertThat(SqlStates.isExclusionViolation(null, null)).isFalse();
    }

    @Test
    void nullMessageDoesNotMatchANamedConstraint() {
        var silent = new SQLException((String) null, "23P01");

        assertThat(SqlStates.isExclusionViolation(silent, SLOT)).isFalse();
        assertThat(SqlStates.isExclusionViolation(silent, null)).isTrue();
    }

    @Test
    void cyclicCauseChainTerminates() {
        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(SqlStates.isExclusionViolation(a, null)).isFalse();
    }

    /**
     * Pins the shape that Boot's jOOQ exception translation produces for PostgreSQL: it uses a
     * {@code SQLErrorCodeSQLExceptionTranslator("PostgreSQL")} and, for SQLSTATE 23P01 (absent from the error-code
     * lists), falls back to the SQLSTATE class 23 mapping. The result must stay inside the
     * {@code DataIntegrityViolationException} family with the driver exception as cause.
     */
    @Test
    void springTranslationOfPostgresStatesKeepsSqlExceptionAndFamily() {
        var translator = new SQLErrorCodeSQLExceptionTranslator("PostgreSQL");

        var exclusion = translator.translate("jOOQ", "insert into appointment ...", exclusion());
        var unique = translator.translate("jOOQ", "insert into client ...", unique());

        assertThat(exclusion).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(unique).isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(SqlStates.isExclusionViolation(exclusion, SLOT)).isTrue();
        assertThat(SqlStates.isUniqueViolation(unique, PHONE)).isTrue();
    }
}
