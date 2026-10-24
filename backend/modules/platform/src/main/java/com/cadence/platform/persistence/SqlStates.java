package com.cadence.platform.persistence;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Recognises PostgreSQL constraint violations behind whatever exception wrapping the data layer produced.
 *
 * <p>A failed jOOQ statement surfaces as a Spring {@code DataIntegrityViolationException} (Boot's jOOQ
 * auto-configuration translates the {@link SQLException}) or as a raw jOOQ {@code DataAccessException}. Both keep
 * the driver's {@link SQLException} in their cause chain, so the check walks the chain and compares SQLSTATE. That
 * is more robust than matching Spring exception subclasses: SQLSTATE {@code 23P01} (exclusion violation) has no
 * Spring-specific subclass and ends up as a plain {@code DataIntegrityViolationException}.
 *
 * <p>The constraint name is matched against the SQL exception message, which PostgreSQL fills with
 * {@code ... violates exclusion constraint "name"} (the name is kept verbatim in localized messages). This avoids a
 * compile-time dependency on the driver's {@code PSQLException}.
 */
public final class SqlStates {

    /** {@code unique_violation}. */
    public static final String UNIQUE_VIOLATION = "23505";
    /** {@code exclusion_violation}. */
    public static final String EXCLUSION_VIOLATION = "23P01";

    private static final int MAX_DEPTH = 32;

    private SqlStates() {
    }

    /** True when the chain holds a unique violation (SQLSTATE 23505); {@code constraint} may be {@code null}. */
    public static boolean isUniqueViolation(Throwable failure, String constraint) {
        return hasViolation(failure, UNIQUE_VIOLATION, constraint);
    }

    /** True when the chain holds an exclusion violation (SQLSTATE 23P01); {@code constraint} may be {@code null}. */
    public static boolean isExclusionViolation(Throwable failure, String constraint) {
        return hasViolation(failure, EXCLUSION_VIOLATION, constraint);
    }

    /**
     * True when some {@link SQLException} in the cause chain (or in its {@code getNextException} chain) carries
     * {@code sqlState} and, if {@code constraint} is not {@code null}, names that constraint in its message.
     */
    public static boolean hasViolation(Throwable failure, String sqlState, String constraint) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.add(current); depth++) {
            if (current instanceof SQLException sql && matches(sql, sqlState, constraint, seen)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean matches(SQLException head, String sqlState, String constraint, Set<Throwable> seen) {
        SQLException sql = head;
        for (int hops = 0; sql != null && hops < MAX_DEPTH; hops++) {
            if (sqlState.equals(sql.getSQLState()) && namesConstraint(sql, constraint)) {
                return true;
            }
            SQLException next = sql.getNextException();
            if (next == null || !seen.add(next)) {
                break;
            }
            sql = next;
        }
        return false;
    }

    private static boolean namesConstraint(SQLException sql, String constraint) {
        if (constraint == null) {
            return true;
        }
        String message = sql.getMessage();
        return message != null && message.contains(constraint);
    }
}
