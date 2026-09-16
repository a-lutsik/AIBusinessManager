package com.cadence.ai.api;

import java.util.List;
import java.util.UUID;

/** Public AI orchestration port — tools call module services only. */
public interface AiAssistantService {

    String moduleName();

    ChatResponse chat(UUID conversationId, String userLocale, String message);

    ChatResponse confirmDraft(UUID draftId);

    ChatResponse rejectDraft(UUID draftId);

    ConversationHistoryView conversation(UUID conversationId);

    List<ChatResponse.DraftAction> listDrafts(String status);
}
