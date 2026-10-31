package com.cadence.ai.internal.tools;

import java.util.List;

/**
 * The allow-list of everything a tool may show the LLM (ADR 0002 §2.3): refs instead of ids and names, tenant-local
 * times, no phone, name, typed name or telegram. Module views are never serialised directly; {@code ToolResultsLeakTest}
 * scans these records.
 */
public final class ToolResults {

    private ToolResults() {
    }

    public sealed interface Result permits Schedule, FreeSlots, ClientProfile, Metrics, DraftCreated {
    }

    public record Schedule(List<VisitRow> visits, boolean truncated) implements Result {
    }

    public record VisitRow(
            String visitRef,
            String masterRef,
            String clientRef,
            String serviceName,
            String start,
            String end,
            String status
    ) {
    }

    public record FreeSlots(List<String> slots, boolean truncated) implements Result {
    }

    public record ClientProfile(
            String clientRef,
            int completedVisits,
            int noShows,
            int lateCancels,
            String firstVisit,
            String lastVisit,
            int completedValueMinor,
            String currency,
            Trust trust,
            List<ProfileVisit> upcoming,
            List<ProfileVisit> recent,
            String notes,
            String tags
    ) implements Result {
    }

    public record Trust(String level, List<String> reasonCodes) {
    }

    public record ProfileVisit(String visitRef, String masterRef, String serviceName, String start, String status) {
    }

    public record Metrics(List<Metric> metrics) implements Result {
    }

    public record Metric(
            String key,
            Double value,
            String unit,
            int sampleSize,
            boolean insufficientData,
            String explanation,
            String windowStart,
            String windowEnd,
            Double deltaMoM,
            List<MasterValue> breakdown
    ) {
    }

    public record MasterValue(String masterRef, Double value, boolean insufficientData) {
    }

    public record DraftCreated(String draftRef, String type, String status, DraftRefs summary) implements Result {
    }

    /** Only the refs and times of the draft; the names are on the owner's card, not here. */
    public record DraftRefs(
            String clientRef,
            String masterRef,
            String serviceRef,
            String visitRef,
            String start,
            String newStart,
            String initiatedBy
    ) {
    }
}
