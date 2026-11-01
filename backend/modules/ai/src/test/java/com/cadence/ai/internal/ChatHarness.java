package com.cadence.ai.internal;

import com.cadence.ai.internal.drafts.DraftApplier;
import com.cadence.ai.internal.drafts.DraftPreflight;
import com.cadence.ai.internal.drafts.DraftWriter;
import com.cadence.ai.internal.llm.FinishReason;
import com.cadence.ai.internal.llm.LlmMessage;
import com.cadence.ai.internal.llm.LlmProvider;
import com.cadence.ai.internal.llm.LlmProviderRegistry;
import com.cadence.ai.internal.llm.LlmRequest;
import com.cadence.ai.internal.llm.LlmResponse;
import com.cadence.ai.internal.llm.StubLlmProvider;
import com.cadence.ai.internal.llm.ToolCall;
import com.cadence.ai.internal.llm.Usage;
import com.cadence.ai.internal.privacy.FreeTextMasker;
import com.cadence.ai.internal.privacy.InMemoryPseudonymStore;
import com.cadence.ai.internal.privacy.OwnerMessageMasker;
import com.cadence.ai.internal.privacy.PseudonymRegistry;
import com.cadence.ai.internal.privacy.Restorer;
import com.cadence.ai.internal.tools.TestTools;
import com.cadence.ai.internal.tools.ToolRegistry;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.Offering;
import com.cadence.catalog.api.ServiceView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.growth.api.GrowthService;
import com.cadence.platform.ai.AiConversationStore;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.ai.AiLogStore;
import com.cadence.platform.ai.AiMessageStore;
import com.cadence.platform.ai.AiPseudonymStore;
import com.cadence.platform.ai.AiSettingsStore;
import com.cadence.platform.ai.AiUsageStore;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.platform.tenant.TenantInfo;
import com.cadence.retention.api.RetentionService;
import com.cadence.retention.api.TrustView;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** The real orchestrator, tools, maskers and draft code over in-memory stores, mocked module ports and the scriptable stub. */
final class ChatHarness {

    static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");
    /** 2026-11-03T10:00 in Asia/Tbilisi. */
    static final Instant SLOT = Instant.parse("2026-11-03T06:00:00Z");
    static final String SLOT_LOCAL = "2026-11-03T10:00";

    static final UUID TENANT = UUID.randomUUID();
    static final UUID CLIENT_ID = UUID.randomUUID();
    static final UUID MASTER_ID = UUID.randomUUID();
    static final UUID SERVICE_ID = UUID.randomUUID();
    static final UUID VISIT_ID = UUID.randomUUID();

    static final String CLIENT_NAME = "Maria Sokolova";
    static final String CLIENT_PHONE = "+995555123456";
    static final String MASTER_NAME = "Nino Beridze";
    static final String TELEGRAM = "tg-77123";
    static final String ENTERED_NAME = "Masha Typed / Maria Sokolova";
    static final String NOTES = "Allergy to dye, tel. +995 555 12 34 56. Prefers mornings with Nino Beridze.";

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final StubLlmProvider stub = new StubLlmProvider();
    final AiPseudonymStore pseudonymStore;
    final PseudonymRegistry pseudonyms;
    final FreeTextMasker masker = new FreeTextMasker();
    final Restorer restorer;
    final FakeConversations conversations = new FakeConversations();
    final FakeMessages messages = new FakeMessages();
    final FakeDrafts drafts = new FakeDrafts();
    final FakeUsage usage = new FakeUsage();
    final FakeSettings settings = new FakeSettings();
    final FakeLogs logs = new FakeLogs();
    final RecordingAudit audit = new RecordingAudit();
    final BookingService booking = mock(BookingService.class);
    final CatalogService catalog = mock(CatalogService.class);
    final CrmService crm = mock(CrmService.class);
    final GrowthService growth = mock(GrowthService.class);
    final RetentionService retention = mock(RetentionService.class);
    final TenantDirectory tenants = mock(TenantDirectory.class);
    final TransactionTemplate tx = new NoOpTransactions();
    final AiProperties properties;
    final AiAssistantServiceImpl service;
    final DraftApplier applier;

    ChatHarness() {
        this(AiProperties.withDefaultLimits("stub", null, null, null, null), List.of());
    }

    ChatHarness(AiProperties properties, List<LlmProvider> extraProviders) {
        this(properties, extraProviders, new InMemoryPseudonymStore());
    }

    ChatHarness(AiProperties properties, List<LlmProvider> extraProviders, AiPseudonymStore pseudonymStore) {
        this.properties = properties;
        this.pseudonymStore = pseudonymStore;
        this.pseudonyms = new PseudonymRegistry(pseudonymStore);
        this.restorer = new Restorer(pseudonyms);
        ActorContext.set(new ActorContext.Actor("owner-1", ActorContext.Role.OWNER, null));
        stubPorts();
        List<LlmProvider> providers = new ArrayList<>(extraProviders);
        providers.add(stub);
        OwnerMessageMasker ownerMasker = new OwnerMessageMasker(
                () -> List.of(new OwnerMessageMasker.Person(MASTER_ID, MASTER_NAME)),
                words -> words.contains("maria")
                        ? List.of(new OwnerMessageMasker.Person(CLIENT_ID, CLIENT_NAME))
                        : List.of(),
                pseudonyms,
                masker);
        DraftPreflight preflight = new DraftPreflight(booking, catalog, crm, clock);
        DraftWriter writer = new DraftWriter(drafts, audit, tx, properties, clock);
        applier = new DraftApplier(drafts, booking, crm, audit, tx, clock);
        ToolRegistry registry = new ToolRegistry(
                TestTools.all(booking, crm, growth, retention, masker, pseudonyms, preflight, writer, clock, properties),
                properties);
        service = new AiAssistantServiceImpl(
                new LlmProviderRegistry(providers, properties), ownerMasker, pseudonyms, restorer,
                conversations, messages, drafts, logs, settings,
                new TokenBudget(usage, properties, audit, tx, clock),
                new SystemPromptFactory(catalog, masker, properties, clock),
                registry, applier, tenants, catalog, audit, properties, tx, clock);
    }

    private void stubPorts() {
        lenient().when(tenants.currentInfo())
                .thenReturn(new TenantInfo(TENANT, "lumen", "Lumen Studio", "Asia/Tbilisi", "GEL", "GE"));
        lenient().when(catalog.rules()).thenReturn(new BookingRulesView(15, 60, 28, true, 24, false, false, 1, true));
        lenient().when(catalog.listServices(false)).thenReturn(List.of(
                new ServiceView(SERVICE_ID, "Haircut", "secret description", 45, 8000, 0, 0, "#fff", true, true)));
        lenient().when(catalog.listSpecialists(false)).thenReturn(List.of(
                new SpecialistView(MASTER_ID, MASTER_NAME, "kc", "#000", true)));
        lenient().when(catalog.offering(MASTER_ID, SERVICE_ID)).thenReturn(Optional.of(offering(true, true)));
        lenient().when(booking.freeSlots(eq(MASTER_ID), eq(SERVICE_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(SLOT));
        lenient().when(crm.findActiveById(CLIENT_ID)).thenReturn(Optional.of(client(NOTES, "vip, +995 555 98 76 54")));
        lenient().when(retention.compute(CLIENT_ID)).thenReturn(new TrustView(
                CLIENT_ID, "HIGH", List.of("Owner trusts " + CLIENT_NAME), null, "override " + CLIENT_NAME, List.of("REGULAR_CLIENT")));
        lenient().when(booking.calendar(any(), any(), any())).thenReturn(List.of(visit()));
        lenient().when(booking.find(VISIT_ID)).thenReturn(Optional.of(visit()));
        lenient().when(booking.clientHistory(CLIENT_ID)).thenReturn(List.of(visit()));
    }

    static Offering offering(boolean offered, boolean active) {
        return new Offering(MASTER_ID, SERVICE_ID, "Haircut", offered, 45, 8000, 0, 0, "#fff", active);
    }

    static ClientView client(String notes, String tags) {
        return new ClientView(CLIENT_ID, CLIENT_PHONE, CLIENT_NAME, notes, tags, "ru", TELEGRAM, true,
                NOW.minus(Duration.ofDays(100)), NOW.minus(Duration.ofDays(10)), 7, 1, 2, 56000);
    }

    /** A confirmed visit tomorrow with every personal field filled in. */
    static AppointmentView visit() {
        return visit(VISIT_ID, AppointmentStatus.CONFIRMED, SLOT.plus(Duration.ofHours(2)));
    }

    static AppointmentView visit(UUID id, AppointmentStatus status, Instant start) {
        return new AppointmentView(id, MASTER_ID, SERVICE_ID, CLIENT_ID, status, start, start.plus(Duration.ofMinutes(45)),
                start, start.plus(Duration.ofMinutes(45)), "Haircut", 8000, 45, 0, 0, "GEL", 0, null,
                ENTERED_NAME, "note about " + CLIENT_NAME, "console", CLIENT_NAME, CLIENT_PHONE, "HIGH", false);
    }

    static LlmResponse toolCall(String name, String args) {
        return new LlmResponse(new LlmMessage.Assistant(null, List.of(new ToolCall("call_" + UUID.randomUUID(), name, args))),
                FinishReason.TOOL_CALLS, new Usage(10, 5));
    }

    static LlmResponse text(String reply) {
        return new LlmResponse(new LlmMessage.Assistant(reply, List.of()), FinishReason.STOP, new Usage(10, 5));
    }

    /** The result the LLM received for the {@code n}-th tool call of the turn's last request (0-based). */
    String toolResult(int n) {
        List<LlmRequest> requests = stub.recordedRequests();
        return requests.get(requests.size() - 1).messages().stream()
                .filter(LlmMessage.ToolResult.class::isInstance)
                .map(m -> ((LlmMessage.ToolResult) m).contentJson())
                .toList()
                .get(n);
    }

    /** Everything that crossed to the provider, as one string. */
    String everythingSent() {
        return stub.recordedRequests().stream().map(r -> r.messages().toString()).reduce("", String::concat);
    }

    /** {@link InMemoryPseudonymStore} to override single calls of. */
    static class DelegatingPseudonyms implements AiPseudonymStore {
        private final AiPseudonymStore delegate = new InMemoryPseudonymStore();

        @Override
        public String refFor(UUID conversationId, Kind kind, UUID entityId, String display) {
            return delegate.refFor(conversationId, kind, entityId, display);
        }

        @Override
        public int replaceDisplay(Kind kind, UUID entityId, String display) {
            return delegate.replaceDisplay(kind, entityId, display);
        }

        @Override
        public Optional<Ref> resolve(UUID conversationId, String token) {
            return delegate.resolve(conversationId, token);
        }

        @Override
        public Optional<String> displayOf(UUID conversationId, String token) {
            return delegate.displayOf(conversationId, token);
        }

        @Override
        public List<Mapping> all(UUID conversationId) {
            return delegate.all(conversationId);
        }
    }

    static final class NoOpTransactions extends TransactionTemplate {
        int executions;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions++;
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
        }
    }

    static final class FakeConversations implements AiConversationStore {
        final Map<UUID, ConversationRecord> rows = new LinkedHashMap<>();
        final List<UUID> touched = new ArrayList<>();

        @Override
        public UUID create(String locale) {
            UUID id = UUID.randomUUID();
            rows.put(id, new ConversationRecord(id, locale, NOW, NOW));
            return id;
        }

        @Override
        public ConversationRecord require(UUID id) {
            ConversationRecord row = rows.get(id);
            if (row == null) {
                throw DomainException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found");
            }
            return row;
        }

        @Override
        public ConversationRecord lockForUpdate(UUID id) {
            return require(id);
        }

        @Override
        public void touch(UUID id, Instant at) {
            touched.add(id);
        }

        @Override
        public int deleteOlderThan(Instant cutoff) {
            return 0;
        }

        @Override
        public int deleteNaming(AiPseudonymStore.Kind kind, UUID entityId) {
            throw new UnsupportedOperationException();
        }
    }

    static final class FakeMessages implements AiMessageStore {
        final List<MessageRecord> rows = new ArrayList<>();

        @Override
        public UUID append(UUID conversationId, Role role, String maskedContent, String provider, String model) {
            UUID id = UUID.randomUUID();
            rows.add(new MessageRecord(id, conversationId, role, maskedContent, provider, model, NOW.plusMillis(rows.size())));
            return id;
        }

        @Override
        public List<MessageRecord> lastN(UUID conversationId, int n) {
            List<MessageRecord> all = list(conversationId);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }

        @Override
        public List<MessageRecord> list(UUID conversationId) {
            return rows.stream().filter(m -> m.conversationId().equals(conversationId)).toList();
        }
    }

    static final class FakeDrafts implements AiDraftStore {
        final Map<UUID, DraftRecord> rows = new LinkedHashMap<>();
        int lockedReads;

        @Override
        public UUID save(UUID conversationId, UUID messageId, String toolName, String payloadJson, Instant expiresAt) {
            UUID id = UUID.randomUUID();
            rows.put(id, new DraftRecord(id, conversationId, messageId, toolName, payloadJson, Status.PENDING,
                    NOW, expiresAt, null, null, null, null, null));
            return id;
        }

        @Override
        public DraftRecord require(UUID id) {
            DraftRecord row = rows.get(id);
            if (row == null) {
                throw DomainException.notFound("DRAFT_NOT_FOUND", "Draft not found");
            }
            return row;
        }

        @Override
        public DraftRecord requireForUpdate(UUID id) {
            lockedReads++;
            return require(id);
        }

        private boolean resolve(UUID id, Status status, UUID applied, String code, String message) {
            DraftRecord r = rows.get(id);
            if (r == null || r.status() != Status.PENDING) {
                return false;
            }
            rows.put(id, new DraftRecord(id, r.conversationId(), r.messageId(), r.toolName(), r.payload(), status, r.createdAt(),
                    r.expiresAt(), status == Status.CONFIRMED ? NOW : null, NOW, applied, code, message));
            return true;
        }

        @Override
        public int countPendingInConversationsNaming(AiPseudonymStore.Kind kind, UUID entityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DraftRecord> lockByClient(UUID clientId) {
            return rows.values().stream().filter(r -> r.payload().contains("\"clientId\":\"" + clientId + "\"")).toList();
        }

        @Override
        public void replacePayload(UUID id, String payloadJson) {
            DraftRecord r = rows.get(id);
            rows.put(id, new DraftRecord(id, r.conversationId(), r.messageId(), r.toolName(), payloadJson, r.status(), r.createdAt(),
                    r.expiresAt(), r.confirmedAt(), r.resolvedAt(), r.appliedEntityId(), r.failureCode(), r.failureMessage()));
        }

        @Override
        public boolean markConfirmed(UUID id, UUID appliedEntityId) {
            return resolve(id, Status.CONFIRMED, appliedEntityId, null, null);
        }

        @Override
        public boolean markRejected(UUID id) {
            return resolve(id, Status.REJECTED, null, null, null);
        }

        @Override
        public boolean markFailed(UUID id, String code, String message) {
            return resolve(id, Status.FAILED, null, code, message);
        }

        @Override
        public boolean markExpired(UUID id) {
            return resolve(id, Status.EXPIRED, null, null, null);
        }

        @Override
        public int expireOverdue(Instant now) {
            int count = 0;
            for (DraftRecord r : List.copyOf(rows.values())) {
                if (r.expiresAt().isBefore(now) && resolve(r.id(), Status.EXPIRED, null, null, null)) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public List<DraftRecord> list(UUID conversationId, Status status) {
            return rows.values().stream()
                    .filter(r -> conversationId == null || r.conversationId().equals(conversationId))
                    .filter(r -> status == null || r.status() == status)
                    .toList();
        }

        @Override
        public List<DraftRecord> listByMessage(UUID messageId) {
            return rows.values().stream().filter(r -> messageId.equals(r.messageId())).toList();
        }
    }

    static final class FakeUsage implements AiUsageStore {
        Usage current = Usage.NONE;
        int marks;

        @Override
        public Usage usage(YearMonth period) {
            return current;
        }

        @Override
        public void add(YearMonth period, long promptTokens, long completionTokens, int llmCalls, int toolCalls) {
            current = new Usage(current.promptTokens() + promptTokens, current.completionTokens() + completionTokens,
                    current.llmCalls() + llmCalls, current.toolCalls() + toolCalls, current.exceededAt());
        }

        @Override
        public boolean markExceeded(YearMonth period) {
            marks++;
            if (current.exceededAt() != null) {
                return false;
            }
            current = new Usage(current.promptTokens(), current.completionTokens(), current.llmCalls(), current.toolCalls(), NOW);
            return true;
        }
    }

    static final class FakeSettings implements AiSettingsStore {
        Settings current = Settings.DEFAULTS;

        @Override
        public Settings get() {
            return current;
        }

        @Override
        public void setNotesToLlm(boolean notesToLlm) {
            current = new Settings(notesToLlm, current.monthlyTokenBudget());
        }

        @Override
        public void setMonthlyTokenBudget(Long monthlyTokenBudget) {
            current = new Settings(current.notesToLlm(), monthlyTokenBudget);
        }
    }

    static final class FakeLogs implements AiLogStore {
        final List<LlmCall> llmCalls = new ArrayList<>();
        final List<ToolCall> toolCalls = new ArrayList<>();

        @Override
        public UUID logLlmCall(LlmCall call) {
            llmCalls.add(call);
            return UUID.randomUUID();
        }

        @Override
        public UUID logToolCall(ToolCall call) {
            toolCalls.add(call);
            return UUID.randomUUID();
        }

        @Override
        public List<LlmCallRecord> listLlmCalls(UUID conversationId) {
            return List.of();
        }

        @Override
        public List<ToolCallRecord> listToolCalls(UUID conversationId) {
            return List.of();
        }
    }

    record AuditRow(String actorId, String actorType, String action, String entityType, UUID entityId, String before,
                    String after, String source, String requestId) {
    }

    static final class RecordingAudit implements AuditLogger {
        final List<AuditRow> rows = new ArrayList<>();

        @Override
        public void record(String actorId, String actorType, String action, String entityType, UUID entityId,
                String beforeJson, String afterJson, String source, String requestId) {
            rows.add(new AuditRow(actorId, actorType, action, entityType, entityId, beforeJson, afterJson, source, requestId));
        }

        List<String> actions() {
            return rows.stream().map(AuditRow::action).toList();
        }
    }
}
