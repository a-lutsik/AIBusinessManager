package com.cadence.ai.internal;

import com.cadence.ai.api.AiAssistantService;
import com.cadence.ai.api.AiSettingsView;
import com.cadence.ai.api.ChatResponse;
import com.cadence.ai.api.ConversationHistoryView;
import com.cadence.ai.internal.drafts.DraftApplier;
import com.cadence.ai.internal.drafts.DraftPayloads;
import com.cadence.ai.internal.llm.FinishReason;
import com.cadence.ai.internal.llm.LlmMessage;
import com.cadence.ai.internal.llm.LlmProvider;
import com.cadence.ai.internal.llm.LlmProviderRegistry;
import com.cadence.ai.internal.llm.LlmRequest;
import com.cadence.ai.internal.llm.LlmResponse;
import com.cadence.ai.internal.llm.ToolCall;
import com.cadence.ai.internal.llm.ToolChoice;
import com.cadence.ai.internal.llm.Usage;
import com.cadence.ai.internal.privacy.OwnerMessageMasker;
import com.cadence.ai.internal.privacy.PseudonymRegistry;
import com.cadence.ai.internal.privacy.Restorer;
import com.cadence.ai.internal.tools.ToolContext;
import com.cadence.ai.internal.tools.ToolRegistry;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.platform.ai.AiConversationStore;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.ai.AiLogStore;
import com.cadence.platform.ai.AiMessageStore;
import com.cadence.platform.ai.AiSettingsStore;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.platform.tenant.TenantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The chat turn of ADR 0002 §2.2 and the owner's draft and settings commands. Deliberately not transactional: no
 * connection is held across an LLM call, every step is its own short transaction through a store or a module port. Lock
 * order: the conversation lock (refs, messages) is only ever taken in transactions of its own, never with a draft row lock
 * or the {@code ai_usage_month} upsert.
 */
@Service
public class AiAssistantServiceImpl implements AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantServiceImpl.class);
    /** ADR 0002 §2.2 step 1: supported conversation locales; anything else is stored as {@code en}. */
    private static final Set<String> LOCALES = Set.of("en", "ru", "ka", "uk", "cs");
    private static final String FINISH_ERROR = "ERROR";
    private static final int CHARS_PER_TOKEN = 4;
    private static final String CUT_MARKER = " […]";
    private static final String TOOL_LIMIT = "TOOL_LIMIT";
    private static final String ANSWER_NOW = "The tool limit of this turn is reached. Answer now using the data already "
            + "obtained, and say what you could not check.";

    private final LlmProviderRegistry providers;
    private final OwnerMessageMasker ownerMessageMasker;
    private final PseudonymRegistry pseudonyms;
    private final Restorer restorer;
    private final AiConversationStore conversations;
    private final AiMessageStore messages;
    private final AiDraftStore drafts;
    private final AiLogStore logs;
    private final AiSettingsStore settings;
    private final TokenBudget budget;
    private final SystemPromptFactory systemPrompts;
    private final ToolRegistry tools;
    private final DraftApplier draftApplier;
    private final TenantDirectory tenants;
    private final CatalogService catalog;
    private final AuditLogger audit;
    private final AiProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AiAssistantServiceImpl(
            LlmProviderRegistry providers,
            OwnerMessageMasker ownerMessageMasker,
            PseudonymRegistry pseudonyms,
            Restorer restorer,
            AiConversationStore conversations,
            AiMessageStore messages,
            AiDraftStore drafts,
            AiLogStore logs,
            AiSettingsStore settings,
            TokenBudget budget,
            SystemPromptFactory systemPrompts,
            ToolRegistry tools,
            DraftApplier draftApplier,
            TenantDirectory tenants,
            CatalogService catalog,
            AuditLogger audit,
            AiProperties properties,
            TransactionTemplate tx,
            Clock clock
    ) {
        this.providers = providers;
        this.ownerMessageMasker = ownerMessageMasker;
        this.pseudonyms = pseudonyms;
        this.restorer = restorer;
        this.conversations = conversations;
        this.messages = messages;
        this.drafts = drafts;
        this.logs = logs;
        this.settings = settings;
        this.budget = budget;
        this.systemPrompts = systemPrompts;
        this.tools = tools;
        this.draftApplier = draftApplier;
        this.tenants = tenants;
        this.catalog = catalog;
        this.audit = audit;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public String moduleName() {
        return "ai";
    }

    @Override
    public ChatResponse chat(UUID conversationId, String userLocale, String message) {
        assertOwner();
        if (message == null || message.isBlank()) {
            throw DomainException.badRequest("AI_MESSAGE_EMPTY", "Message is empty");
        }
        if (message.length() > properties.maxUserMessageChars()) {
            throw DomainException.badRequest("AI_MESSAGE_TOO_LONG", "Message is too long");
        }
        String locale = locale(userLocale);
        if (conversationId != null) {
            conversations.require(conversationId);
        }
        TenantInfo tenant = tenants.currentInfo();
        AiSettingsStore.Settings tenantSettings = settings.get();
        long limit = budget.limit(tenantSettings);
        // Before anything is written, a new conversation included.
        budget.assertAvailable(tenant, limit);
        UUID convo = conversationId != null ? conversationId : conversations.create(locale);

        Turn turn = new Turn(tenant, convo, limit);
        log.info("AI turn started: tenant={}, conversation={}, locale={}, chars={}",
                tenant.id(), convo, locale, message.length());
        try {
            String masked = ownerMessageMasker.mask(convo, message);
            List<AiMessageStore.MessageRecord> history = messages.lastN(convo, properties.maxHistoryMessages());
            UUID userMessageId = append(convo, AiMessageStore.Role.USER, masked, null, null);
            List<SpecialistView> specialists = catalog.listSpecialists(false);
            BookingRulesView rules = catalog.rules();
            ToolContext context = new ToolContext(convo, userMessageId, tenant, rules, tenantSettings.notesToLlm(),
                    specialists.stream().collect(Collectors.toMap(SpecialistView::id, SpecialistView::displayName, (a, b) -> a)),
                    properties.maxDraftsPerTurn(), pseudonyms, tx);
            turn.context = context;

            List<LlmMessage> sent = new ArrayList<>(history.size() + 2);
            sent.add(new LlmMessage.System(systemPrompts.build(context, locale, specialists)));
            for (AiMessageStore.MessageRecord past : history) {
                sent.add(past.role() == AiMessageStore.Role.USER
                        ? new LlmMessage.User(past.content())
                        : new LlmMessage.Assistant(past.content(), List.of()));
            }
            sent.add(new LlmMessage.User(masked));

            String text = runLoop(turn, sent);

            append(convo, AiMessageStore.Role.ASSISTANT, text, turn.provider, turn.model);
            List<ChatResponse.DraftAction> created = actions(drafts.listByMessage(userMessageId));
            TokenBudget.Month month = budget.month(tenant, limit);
            turn.outcome = "ok";
            return new ChatResponse(restorer.restore(convo, text), created, convo,
                    new ChatResponse.UsageView(month.used(), month.limit()));
        } catch (DomainException e) {
            turn.outcome = e.code();
            throw e;
        } finally {
            log.info("AI turn finished: tenant={}, conversation={}, outcome={}, iterations={}, toolCalls={}, promptTokens={}, "
                            + "completionTokens={}, finishReason={}, durationMs={}",
                    tenant.id(), convo, turn.outcome, turn.iterations, turn.toolCalls, turn.promptTokens,
                    turn.completionTokens, turn.finishReason, (System.nanoTime() - turn.startedNanos) / 1_000_000);
        }
    }

    /** Steps 6 and 7 of the turn: the final masked text. */
    private String runLoop(Turn turn, List<LlmMessage> sent) {
        while (turn.iterations < properties.maxToolIterations() && turn.toolCalls < properties.maxToolCallsPerTurn()) {
            LlmResponse response = callLlm(turn, sent, ToolChoice.AUTO);
            LlmMessage.Assistant answer = response.message();
            if (!answer.hasToolCalls()) {
                return finalText(turn, response);
            }
            sent.add(tools.forHistory(answer));
            runTools(turn, answer.toolCalls(), sent);
        }
        log.info("AI turn limit reached, asking for the final answer: conversation={}, iterations={}, toolCalls={}",
                turn.conversationId, turn.iterations, turn.toolCalls);
        sent.add(new LlmMessage.System(ANSWER_NOW));
        LlmResponse last = callLlm(turn, sent, ToolChoice.NONE);
        if (last.message().hasToolCalls()) {
            throw DomainException.badGateway("LLM_UNAVAILABLE", "The model did not answer");
        }
        return finalText(turn, last);
    }

    private void runTools(Turn turn, List<ToolCall> calls, List<LlmMessage> sent) {
        int executed = 0;
        for (ToolCall call : calls) {
            long started = System.nanoTime();
            // Every call must get an answer or the provider rejects the next request, so the excess is refused, not dropped.
            boolean allowed = turn.toolCalls < properties.maxToolCallsPerTurn();
            ToolRegistry.Execution result = allowed
                    ? tools.execute(turn.context, call)
                    : tools.refused(TOOL_LIMIT, "The tool call limit of this turn is reached");
            if (allowed) {
                turn.toolCalls++;
                executed++;
            }
            int durationMs = (int) ((System.nanoTime() - started) / 1_000_000);
            log.debug("AI tool call done: conversation={}, tool={}, errorCode={}, durationMs={}",
                    turn.conversationId, call.name(), result.errorCode(), durationMs);
            logs.logToolCall(new AiLogStore.ToolCall(turn.conversationId, turn.llmCallId, call.name(), call.argumentsJson(),
                    result.resultJson(),
                    result.errorCode() == null ? AiLogStore.ToolStatus.OK : AiLogStore.ToolStatus.ERROR,
                    result.errorCode(), durationMs));
            sent.add(new LlmMessage.ToolResult(call.id(), call.name(), result.resultJson()));
        }
        if (executed > 0) {
            budget.recordToolCalls(turn.tenant, executed);
        }
    }

    /** Budget check, the provider call with failover, then usage of whichever provider answered and the prompt log. */
    private LlmResponse callLlm(Turn turn, List<LlmMessage> sent, ToolChoice choice) {
        budget.assertAvailable(turn.tenant, turn.limit);
        // tools go along with NONE as well: tool_choice is only serialised with them, and past tool messages need them.
        LlmRequest request = new LlmRequest(sent, tools.specs(), choice, properties.maxCompletionTokens());
        String prompt = LlmTranscript.messages(sent);
        LlmProvider provider = providers.primary();
        long started = System.nanoTime();
        LlmResponse response;
        try {
            response = attempt(turn, provider, request, prompt, started);
        } catch (DomainException e) {
            LlmProvider fallback = providers.fallback();
            if (fallback == null || fallback == provider) {
                throw e;
            }
            log.warn("Primary LLM provider failed, failing over: conversation={}, provider={}, code={}, fallback={}",
                    turn.conversationId, provider.provider(), e.code(), fallback.provider());
            provider = fallback;
            started = System.nanoTime();
            response = attempt(turn, provider, request, prompt, started);
        }
        int latencyMs = millisSince(started);
        Usage usage = countedUsage(turn, response, prompt);
        turn.iterations++;
        turn.provider = provider.provider();
        turn.model = provider.model();
        turn.context.respondedBy(turn.provider, turn.model);
        turn.finishReason = response.finishReason();
        turn.promptTokens += usage.promptTokens();
        turn.completionTokens += usage.completionTokens();
        budget.record(turn.tenant, usage);
        turn.llmCallId = logs.logLlmCall(new AiLogStore.LlmCall(turn.conversationId, turn.context.userMessageId(),
                turn.provider, turn.model, prompt, response.message().content(),
                usage.promptTokens(), usage.completionTokens(), response.finishReason().name(),
                latencyMs, LlmTranscript.toolCalls(response.message().toolCalls())));
        log.debug("AI LLM call done: conversation={}, iteration={}, choice={}, provider={}, finishReason={}, latencyMs={}, "
                        + "promptTokens={}, completionTokens={}",
                turn.conversationId, turn.iterations, choice, turn.provider, response.finishReason(), latencyMs,
                usage.promptTokens(), usage.completionTokens());
        return response;
    }

    /** A failed attempt is logged as well: the masked prompt may have reached the subprocessor. */
    private LlmResponse attempt(Turn turn, LlmProvider provider, LlmRequest request, String prompt, long started) {
        try {
            return provider.chat(request);
        } catch (RuntimeException e) {
            logs.logLlmCall(new AiLogStore.LlmCall(turn.conversationId, turn.context.userMessageId(), provider.provider(),
                    provider.model(), prompt, null, null, null, FINISH_ERROR, millisSince(started), null));
            throw e;
        }
    }

    /** A provider that reports no usage is counted at characters / 4, so the budget is not silently unspent. */
    private static Usage countedUsage(Turn turn, LlmResponse response, String prompt) {
        if (response.usage().total() > 0) {
            return response.usage();
        }
        LlmMessage.Assistant answer = response.message();
        String calls = LlmTranscript.toolCalls(answer.toolCalls());
        int completionChars = (answer.content() == null ? 0 : answer.content().length()) + (calls == null ? 0 : calls.length());
        Usage estimate = new Usage(prompt.length() / CHARS_PER_TOKEN, completionChars / CHARS_PER_TOKEN);
        log.warn("LLM reported no usage, counting an estimate: conversation={}, promptTokens={}, completionTokens={}",
                turn.conversationId, estimate.promptTokens(), estimate.completionTokens());
        return estimate;
    }

    private static int millisSince(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }

    /** A reply cut by the token limit says so with a locale-neutral marker, in the stored text and for the owner. */
    private static String finalText(Turn turn, LlmResponse response) {
        String content = response.message().content();
        if (content == null || content.isBlank()) {
            throw DomainException.badGateway("LLM_UNAVAILABLE", "The model did not answer");
        }
        if (response.finishReason() != FinishReason.LENGTH) {
            return content;
        }
        log.warn("AI reply cut by the completion token limit: conversation={}", turn.conversationId);
        return content + CUT_MARKER;
    }

    /** The message and the conversation's last-message time together. */
    private UUID append(UUID conversationId, AiMessageStore.Role role, String masked, String provider, String model) {
        return tx.execute(status -> {
            UUID id = messages.append(conversationId, role, masked, provider, model);
            conversations.touch(conversationId, clock.instant());
            return id;
        });
    }

    @Override
    public ChatResponse.DraftAction confirmDraft(UUID draftId) {
        assertOwner();
        return actions(List.of(draftApplier.confirm(draftId))).get(0);
    }

    @Override
    public ChatResponse.DraftAction rejectDraft(UUID draftId) {
        assertOwner();
        AiDraftStore.DraftRecord rejected = tx.execute(status -> {
            AiDraftStore.DraftRecord draft = drafts.require(draftId);
            if (!drafts.markRejected(draftId)) {
                throw DomainException.conflict("DRAFT_NOT_PENDING", "Draft already handled");
            }
            audit.record(ownerId(), "OWNER", "ai.draft.reject", "ai_draft", draftId,
                    DraftPayloads.forAudit(draft.payload()), "{\"status\":\"REJECTED\"}", "ai-draft", draftId.toString());
            return drafts.require(draftId);
        });
        log.info("AI draft rejected: draft={}", draftId);
        return actions(List.of(rejected)).get(0);
    }

    @Override
    public ConversationHistoryView conversation(UUID conversationId) {
        assertOwner();
        conversations.require(conversationId);
        drafts.expireOverdue(clock.instant());
        List<ChatResponse.DraftAction> allDrafts = new ArrayList<>();
        Map<UUID, List<ChatResponse.DraftAction>> byMessage = new HashMap<>();
        UnaryOperator<String> restore = restorer.forConversation(conversationId);
        for (AiDraftStore.DraftRecord draft : drafts.list(conversationId, null)) {
            ChatResponse.DraftAction action = DraftPayloads.toAction(draft, restore);
            allDrafts.add(action);
            if (draft.messageId() != null) {
                byMessage.computeIfAbsent(draft.messageId(), id -> new ArrayList<>()).add(action);
            }
        }
        List<ConversationHistoryView.MessageView> views = messages.list(conversationId).stream()
                .map(m -> new ConversationHistoryView.MessageView(
                        m.id(),
                        m.createdAt(),
                        m.role().name().toLowerCase(Locale.ROOT),
                        restore.apply(m.content()),
                        m.provider(),
                        m.model(),
                        byMessage.getOrDefault(m.id(), List.of())))
                .toList();
        return new ConversationHistoryView(conversationId, views, allDrafts);
    }

    @Override
    public List<ChatResponse.DraftAction> listDrafts(String status) {
        assertOwner();
        AiDraftStore.Status filter = draftStatus(status);
        int expired = drafts.expireOverdue(clock.instant());
        if (expired > 0) {
            log.info("AI drafts expired on read: count={}", expired);
        }
        return actions(drafts.list(null, filter));
    }

    /** The cards of drafts of any conversations, with one read of each conversation's refs at most. */
    private List<ChatResponse.DraftAction> actions(List<AiDraftStore.DraftRecord> records) {
        Map<UUID, UnaryOperator<String>> restorers = new HashMap<>();
        return records.stream()
                .map(draft -> DraftPayloads.toAction(draft,
                        restorers.computeIfAbsent(draft.conversationId(), restorer::forConversation)))
                .toList();
    }

    private static AiDraftStore.Status draftStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AiDraftStore.Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw DomainException.badRequest("INVALID_DRAFT_STATUS", "Unknown draft status");
        }
    }

    @Override
    public AiSettingsView getSettings() {
        assertOwner();
        return settingsView(settings.get());
    }

    @Override
    public AiSettingsView setNotesToLlm(boolean notesToLlm) {
        assertOwner();
        TenantInfo tenant = tenants.currentInfo();
        AiSettingsStore.Settings updated = tx.execute(status -> {
            AiSettingsStore.Settings before = settings.get();
            settings.setNotesToLlm(notesToLlm);
            audit.record(ownerId(), "OWNER", "ai.settings.update", "ai_tenant_settings", tenant.id(),
                    "{\"notesToLlm\":" + before.notesToLlm() + "}", "{\"notesToLlm\":" + notesToLlm + "}",
                    "ai-settings", null);
            return new AiSettingsStore.Settings(notesToLlm, before.monthlyTokenBudget());
        });
        log.info("AI settings updated: tenant={}, notesToLlm={}", tenant.id(), notesToLlm);
        return settingsView(updated);
    }

    private AiSettingsView settingsView(AiSettingsStore.Settings current) {
        TokenBudget.Month month = budget.month(tenants.currentInfo(), budget.limit(current));
        return new AiSettingsView(current.notesToLlm(), month.limit(), month.used(), month.period().toString(), month.resetsAt());
    }

    private static String locale(String requested) {
        return requested != null && LOCALES.contains(requested) ? requested : "en";
    }

    private static String ownerId() {
        return ActorContext.require().actorId();
    }

    private static void assertOwner() {
        boolean owner = ActorContext.current().map(ActorContext.Actor::isOwner).orElse(false);
        if (!owner) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "AI assistant is owner-only");
        }
    }

    /** Counters of one turn, for its limits and its log line. */
    private static final class Turn {
        final TenantInfo tenant;
        final UUID conversationId;
        final long limit;
        final long startedNanos = System.nanoTime();
        ToolContext context;
        int iterations;
        int toolCalls;
        long promptTokens;
        long completionTokens;
        FinishReason finishReason;
        UUID llmCallId;
        String provider;
        String model;
        String outcome = "error";

        Turn(TenantInfo tenant, UUID conversationId, long limit) {
            this.tenant = tenant;
            this.conversationId = conversationId;
            this.limit = limit;
        }
    }
}
