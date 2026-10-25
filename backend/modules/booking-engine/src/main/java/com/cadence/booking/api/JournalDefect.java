package com.cadence.booking.api;

import java.util.UUID;

/**
 * A place where the booking journal cannot be trusted (ADR 0001). Kinds: {@link #NO_CREATED} (a visit whose first
 * event is not CREATED, or a visit row without events), {@link #CHAIN_BREAK} (an event whose {@code from} is not the
 * folded current value; the event is applied anyway), {@link #ROW_MISMATCH} (fold and {@code appointment} row
 * disagree on a fact field), {@link #ORPHAN_EVENT} (events of a visit that has no row).
 */
public record JournalDefect(UUID appointmentId, String kind, String detail) {

    public static final String NO_CREATED = "NO_CREATED";
    public static final String CHAIN_BREAK = "CHAIN_BREAK";
    public static final String ROW_MISMATCH = "ROW_MISMATCH";
    public static final String ORPHAN_EVENT = "ORPHAN_EVENT";
}
