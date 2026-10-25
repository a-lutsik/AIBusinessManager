package com.cadence.booking.api;

import java.time.Instant;
import java.util.List;

/**
 * Read port over the booking journal (ADR 0001). Growth metrics are computed from {@link #fold} only, so every
 * metric can be replayed by hand from {@code appointment_event}; {@code appointment} rows are a checked derivative.
 */
public interface BookingJournal {

    /**
     * All visits of the current tenant after folding their events with {@code created_at <= asOf}. Only OWNER and
     * SYSTEM actors ({@code FORBIDDEN_RESOURCE}, 403, for anyone else, MASTER included).
     *
     * @throws IllegalStateException for an event type or payload the fold does not know: the tenant's recalculation
     *     fails loudly instead of computing from a journal it cannot read
     */
    JournalFold fold(Instant asOf);

    /**
     * {@code fold(asOf)} checked against the {@code appointment} rows created up to {@code asOf}: the fold's own
     * defects plus field mismatches, visit rows without events and events without a visit row. Same access rule.
     */
    List<JournalDefect> reconcile(Instant asOf);
}
