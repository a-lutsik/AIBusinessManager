package com.cadence.booking.api;

import java.time.Instant;
import java.util.List;

/** Result of folding the booking journal up to {@code asOf}: visit facts plus the defects found on the way. */
public record JournalFold(Instant asOf, List<VisitFact> facts, List<JournalDefect> defects) {

    public JournalFold {
        facts = List.copyOf(facts);
        defects = List.copyOf(defects);
    }
}
