package com.cadence.ai.api;

import java.util.List;
import java.util.UUID;

/** Public AI orchestration port — tools call module services only. Every method is owner-only (403 otherwise). */
public interface AiAssistantService {

    String moduleName();

    /**
     * One chat turn (ADR 0002 §2.2).
     *
     * @throws com.cadence.platform.error.DomainException 400 {@code AI_MESSAGE_EMPTY} / {@code AI_MESSAGE_TOO_LONG},
     *     404 {@code CONVERSATION_NOT_FOUND}, 429 {@code AI_BUDGET_EXCEEDED}, 502 {@code LLM_UNAVAILABLE} /
     *     {@code LLM_UNAUTHORIZED}
     */
    ChatResponse chat(UUID conversationId, String userLocale, String message);

    /**
     * Applies a draft through the Booking Engine (ADR 0002 §4.3).
     *
     * @throws com.cadence.platform.error.DomainException 404 {@code DRAFT_NOT_FOUND}, 409 {@code DRAFT_NOT_PENDING} /
     *     {@code DRAFT_EXPIRED} / {@code DRAFT_STALE}, or the engine's own refusal (the draft becomes {@code FAILED})
     */
    ChatResponse.DraftAction confirmDraft(UUID draftId);

    /** @throws com.cadence.platform.error.DomainException 404 {@code DRAFT_NOT_FOUND}, 409 {@code DRAFT_NOT_PENDING} */
    ChatResponse.DraftAction rejectDraft(UUID draftId);

    /** @throws com.cadence.platform.error.DomainException 404 {@code CONVERSATION_NOT_FOUND} */
    ConversationHistoryView conversation(UUID conversationId);

    /**
     * Drafts of the tenant, newest first, at most 100; overdue {@code PENDING} ones become {@code EXPIRED} first.
     *
     * @param status optional filter; an unknown value is 400 {@code INVALID_DRAFT_STATUS}
     */
    List<ChatResponse.DraftAction> listDrafts(String status);

    AiSettingsView getSettings();

    /** Whether masked client notes and tags may reach the LLM (audit {@code ai.settings.update}). */
    AiSettingsView setNotesToLlm(boolean notesToLlm);
}
