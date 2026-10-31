package com.cadence.ai.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One chat turn (ADR 0002 §8): the restored reply, the drafts created in this turn and the tenant's month usage. */
public record ChatResponse(
        String reply,
        List<DraftAction> drafts,
        UUID conversationId,
        UsageView usage
) {
    public record UsageView(long monthTokensUsed, long monthTokenBudget) {
    }

    /**
     * A draft card. {@code type} is {@code draft_booking}, {@code draft_reschedule} or {@code draft_cancel};
     * {@code status} is {@code PENDING}, {@code CONFIRMED}, {@code REJECTED}, {@code FAILED} or {@code EXPIRED}.
     * {@code summary} is null only for a stored payload this version cannot read.
     */
    public record DraftAction(
            UUID id,
            String type,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant resolvedAt,
            UUID appliedEntityId,
            String failureCode,
            String failureMessage,
            DraftSummary summary
    ) {
    }

    /**
     * What the owner confirms, built on the server from entities, never from model text (except {@code reason}).
     * Times are tenant-local {@code YYYY-MM-DDTHH:MM}. Common: {@code clientName}, {@code specialistName},
     * {@code serviceName}. {@code draft_booking}: {@code durationMinutes}, {@code priceMinor}, {@code currencyCode},
     * {@code localStart}. {@code draft_reschedule}: {@code localFrom}, {@code localTo}. {@code draft_cancel}:
     * {@code localStart}, {@code initiatedBy} ({@code business} or {@code client}), {@code reason} (plain text, may be
     * null). Fields of the other types are null.
     */
    public record DraftSummary(
            String clientName,
            String specialistName,
            String serviceName,
            Integer durationMinutes,
            Integer priceMinor,
            String currencyCode,
            String localStart,
            String localFrom,
            String localTo,
            String initiatedBy,
            String reason
    ) {
    }
}
