package com.aibusinessmanager.ai.internal;

import com.aibusinessmanager.ai.api.AiAssistantService;
import com.aibusinessmanager.ai.api.ChatResponse;
import com.aibusinessmanager.ai.api.ConversationHistoryView;
import com.aibusinessmanager.booking.api.AppointmentView;
import com.aibusinessmanager.booking.api.BookingService;
import com.aibusinessmanager.booking.api.CreateBookingCommand;
import com.aibusinessmanager.crm.api.ClientView;
import com.aibusinessmanager.crm.api.CrmService;
import com.aibusinessmanager.growth.api.GrowthService;
import com.aibusinessmanager.growth.api.MetricView;
import com.aibusinessmanager.platform.ai.AiStore;
import com.aibusinessmanager.platform.audit.AuditLogger;
import com.aibusinessmanager.platform.error.DomainException;
import com.aibusinessmanager.platform.tenancy.ActorContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AiAssistantServiceImpl implements AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantServiceImpl.class);

    private final LlmProviderRegistry registry;
    private final Pseudonymizer pseudonymizer;
    private final BookingService bookingService;
    private final CrmService crmService;
    private final GrowthService growthService;
    private final AuditLogger auditLogger;
    private final AiStore aiStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiAssistantServiceImpl(
            LlmProviderRegistry registry,
            Pseudonymizer pseudonymizer,
            BookingService bookingService,
            CrmService crmService,
            GrowthService growthService,
            AuditLogger auditLogger,
            AiStore aiStore
    ) {
        this.registry = registry;
        this.pseudonymizer = pseudonymizer;
        this.bookingService = bookingService;
        this.crmService = crmService;
        this.growthService = growthService;
        this.auditLogger = auditLogger;
        this.aiStore = aiStore;
    }

    @Override
    public String moduleName() {
        return "ai";
    }

    @Override
    public ChatResponse chat(UUID conversationId, String userLocale, String message) {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor != null && actor.isMaster()) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "AI assistant is owner-only");
        }
        UUID convo = conversationId == null ? UUID.randomUUID() : conversationId;
        String facts = collectFacts(convo);
        String system = "You are the AI business manager. Locale=" + userLocale
                + ". Never apply changes yourself; propose drafts. Facts:\n" + facts;
        String masked = pseudonymizer.mask(message);
        LlmProvider used = registry.primary();
        String reply = complete(used, system, masked);
        aiStore.logPrompt(convo, used.provider(), used.model(), masked, reply);
        List<ChatResponse.DraftAction> drafts = new ArrayList<>();
        return new ChatResponse(pseudonymizer.restore(convo, reply), drafts, convo);
    }

    private String complete(LlmProvider provider, String system, String prompt) {
        try {
            return provider.complete(system, prompt);
        } catch (DomainException e) {
            LlmProvider fallback = registry.fallback();
            if (fallback == null || fallback == provider) {
                throw e;
            }
            log.warn("Primary LLM provider '{}' failed ({}), failing over to '{}'",
                    provider.provider(), e.code(), fallback.provider());
            return fallback.complete(system, prompt);
        }
    }

    @Override
    @Transactional
    public ChatResponse confirmDraft(UUID draftId) {
        AiStore.DraftRecord rec = aiStore.requireDraft(draftId);
        if (!"PENDING".equals(rec.status())) {
            throw DomainException.conflict("DRAFT_NOT_PENDING", "Draft already handled");
        }
        try {
            JsonNode node = mapper.readTree(rec.payload());
            if ("draft_booking".equals(rec.toolName())) {
                bookingService.create(new CreateBookingCommand(
                        UUID.fromString(node.get("specialistId").asText()),
                        UUID.fromString(node.get("serviceId").asText()),
                        Instant.parse(node.get("serviceStart").asText()),
                        UUID.fromString(node.get("clientId").asText()),
                        null,
                        null,
                        null,
                        false,
                        null,
                        "ai",
                        "AI",
                        null,
                        false
                ));
            }
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw DomainException.badRequest("DRAFT_INVALID", "Draft payload could not be applied");
        }
        aiStore.markConfirmed(draftId);
        auditLogger.record(
                ActorContext.current().map(ActorContext.Actor::actorId).orElse("owner"),
                "OWNER",
                "ai.draft.confirm",
                "ai_draft",
                draftId,
                rec.payload(),
                "{\"status\":\"CONFIRMED\"}",
                "ai",
                null
        );
        return new ChatResponse("Draft applied through Booking Engine.", List.of(), rec.conversationId());
    }

    @Override
    @Transactional
    public ChatResponse rejectDraft(UUID draftId) {
        AiStore.DraftRecord rec = aiStore.requireDraft(draftId);
        if (!"PENDING".equals(rec.status())) {
            throw DomainException.conflict("DRAFT_NOT_PENDING", "Draft already handled");
        }
        aiStore.markRejected(draftId);
        auditLogger.record(
                ActorContext.current().map(ActorContext.Actor::actorId).orElse("owner"),
                "OWNER",
                "ai.draft.reject",
                "ai_draft",
                draftId,
                rec.payload(),
                "{\"status\":\"REJECTED\"}",
                "ai",
                null
        );
        return new ChatResponse("Draft rejected.", List.of(), rec.conversationId());
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationHistoryView conversation(UUID conversationId) {
        assertOwner();
        List<ConversationHistoryView.MessageView> messages = new ArrayList<>();
        for (AiStore.PromptRecord prompt : aiStore.listPrompts(conversationId)) {
            messages.add(new ConversationHistoryView.MessageView(
                    prompt.createdAt(), "user", prompt.prompt(), prompt.provider(), prompt.model()
            ));
            if (prompt.response() != null && !prompt.response().isBlank()) {
                messages.add(new ConversationHistoryView.MessageView(
                        prompt.createdAt(), "assistant", prompt.response(), prompt.provider(), prompt.model()
                ));
            }
        }
        List<ChatResponse.DraftAction> drafts = aiStore.listDrafts(conversationId, null).stream()
                .map(this::toDraftAction)
                .toList();
        return new ConversationHistoryView(conversationId, messages, drafts);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatResponse.DraftAction> listDrafts(String status) {
        assertOwner();
        return aiStore.listDrafts(null, status).stream().map(this::toDraftAction).toList();
    }

    private ChatResponse.DraftAction toDraftAction(AiStore.DraftRecord rec) {
        String summary = rec.toolName() + " (" + rec.status() + ")";
        return new ChatResponse.DraftAction(rec.id(), rec.toolName(), summary, rec.payload(), rec.status());
    }

    private void assertOwner() {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor != null && actor.isMaster()) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "AI assistant is owner-only");
        }
    }

    private String collectFacts(UUID convo) {
        StringBuilder sb = new StringBuilder();
        try {
            List<MetricView> metrics = growthService.currentMetrics(null);
            for (MetricView metric : metrics) {
                sb.append(metric.key()).append("=")
                        .append(metric.insufficientData() ? "insufficient" : metric.value())
                        .append(" (")
                        .append(metric.explanation())
                        .append(")\n");
            }
        } catch (Exception ignored) {
            sb.append("metrics unavailable\n");
        }
        try {
            Instant from = Instant.now();
            Instant to = from.plusSeconds(86400);
            List<AppointmentView> today = bookingService.calendar(from, to, null);
            sb.append("visits_next_24h=").append(today.size()).append('\n');
            for (AppointmentView visit : today) {
                ClientView client = crmService.findById(visit.clientId()).orElse(null);
                String name = client == null ? "client" : pseudonymizer.tokenForName(convo, client.displayName());
                sb.append(visit.serviceStart()).append(' ').append(visit.serviceNameSnapshot()).append(' ').append(name).append('\n');
            }
        } catch (Exception ignored) {
            sb.append("schedule unavailable\n");
        }
        return sb.toString();
    }
}
