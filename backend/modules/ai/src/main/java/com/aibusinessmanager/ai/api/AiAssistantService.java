package com.aibusinessmanager.ai.api;

import java.util.List;
import java.util.UUID;

/** Public AI orchestration port — tools call module services only. */
public interface AiAssistantService {

    String moduleName();

    ChatResponse chat(UUID conversationId, String userLocale, String message);

    ChatResponse confirmDraft(UUID draftId);
}
