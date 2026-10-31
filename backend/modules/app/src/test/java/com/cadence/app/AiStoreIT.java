package com.cadence.app;

import com.cadence.platform.ai.AiConversationStore;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.ai.AiDraftStore.DraftRecord;
import com.cadence.platform.ai.AiDraftStore.Status;
import com.cadence.platform.ai.AiLogStore;
import com.cadence.platform.ai.AiMessageStore;
import com.cadence.platform.ai.AiMessageStore.MessageRecord;
import com.cadence.platform.ai.AiMessageStore.Role;
import com.cadence.platform.ai.AiPseudonymStore;
import com.cadence.platform.ai.AiPseudonymStore.Kind;
import com.cadence.platform.ai.AiSettingsStore;
import com.cadence.platform.ai.AiText;
import com.cadence.platform.ai.AiUsageStore;
import com.cadence.platform.ai.JooqAiMessageStore;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.platform.tenant.TenantInfo;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0002 §3, §4, §5, §6, §7, §9: the platform AI stores against the real V15 schema, with the pool connected as the
 * RLS runtime role {@code cadence_app} (AbstractTimescaleIT). Every test runs in two fresh tenants; tenant B must see
 * nothing of tenant A through the stores (soft barrier) and through plain SQL as {@code cadence_app} (hard barrier).
 */
@SpringBootTest
class AiStoreIT extends AbstractTimescaleIT {

    /** Postgres SQLSTATE insufficient_privilege: RLS WITH CHECK violations. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** Postgres SQLSTATE foreign_key_violation. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static final List<String> AI_TABLES = List.of(
            "ai_tenant_settings", "ai_conversation", "ai_message", "ai_pseudonym", "ai_usage_month",
            "ai_draft", "ai_prompt_log", "ai_tool_call");

    @Autowired
    AiConversationStore conversations;
    @Autowired
    AiMessageStore messages;
    @Autowired
    AiPseudonymStore pseudonyms;
    @Autowired
    AiDraftStore drafts;
    @Autowired
    AiUsageStore usage;
    @Autowired
    AiSettingsStore settings;
    @Autowired
    AiLogStore logs;
    @Autowired
    TenantDirectory tenants;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    Clock clock;
    @Autowired
    DSLContext dsl;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void freshTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        tenants.upsert(tenantA, "ai-a-" + tenantA, "AI Studio A", "Asia/Tbilisi", "GEL", "GE");
        tenants.upsert(tenantB, "ai-b-" + tenantB, "AI Studio B", "Europe/Prague", "CZK", "CZ");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static <T> T as(UUID tenant, Supplier<T> call) {
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            return call.get();
        }
    }

    private static void run(UUID tenant, Runnable call) {
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            call.run();
        }
    }

    private <T> T inTransaction(UUID tenant, Supplier<T> call) {
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            return new TransactionTemplate(transactionManager).execute(status -> call.get());
        }
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private static void bindTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.current_tenant_id', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }

    private static long count(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** SQLSTATE of the first {@link SQLException} in the cause chain, or null. */
    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void assertDomain(ThrowingCallable call, String code, int status) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(code);
                    assertThat(ex.status()).isEqualTo(status);
                });
    }

    private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** One row in every AI table of {@code tenant}; returns the conversation, message, draft and LLM call ids. */
    private record Written(UUID conversation, UUID message, UUID draft, UUID llmCall) {
    }

    private Written writeEverything(UUID tenant, YearMonth period) {
        return as(tenant, () -> {
            UUID conversation = conversations.create("en");
            UUID message = messages.append(conversation, Role.USER, "How is CLIENT_1 doing?", null, null);
            pseudonyms.refFor(conversation, Kind.CLIENT, UUID.randomUUID(), "Nino Beridze");
            UUID draft = drafts.save(conversation, message, "draft_booking", "{\"v\":1}", clock.instant().plus(Duration.ofHours(24)));
            usage.add(period, 100, 20, 1, 1);
            settings.setNotesToLlm(true);
            UUID llmCall = logs.logLlmCall(new AiLogStore.LlmCall(conversation, message, "stub", "stub-1",
                    "[{\"role\":\"user\"}]", "{\"content\":\"ok\"}", 100, 20, "STOP", 12, null));
            logs.logToolCall(new AiLogStore.ToolCall(conversation, llmCall, "get_metrics", "{}", "{\"metrics\":[]}",
                    AiLogStore.ToolStatus.OK, null, 3));
            return new Written(conversation, message, draft, llmCall);
        });
    }

    // ------------------------------------------------------------------ tenant isolation

    @Test
    void tenantBSeesNothingOfTenantAThroughAnyStore() {
        YearMonth period = YearMonth.of(2026, 10);
        Written a = writeEverything(tenantA, period);

        run(tenantB, () -> {
            assertDomain(() -> conversations.require(a.conversation()), "CONVERSATION_NOT_FOUND", 404);
            assertDomain(() -> inTransaction(tenantB, () -> conversations.lockForUpdate(a.conversation())),
                    "CONVERSATION_NOT_FOUND", 404);
            assertThat(messages.list(a.conversation())).isEmpty();
            assertThat(messages.lastN(a.conversation(), 20)).isEmpty();
            assertDomain(() -> messages.append(a.conversation(), Role.USER, "x", null, null), "CONVERSATION_NOT_FOUND", 404);
            assertThat(pseudonyms.resolve(a.conversation(), "CLIENT_1")).isEmpty();
            assertThat(pseudonyms.displayOf(a.conversation(), "CLIENT_1")).isEmpty();
            assertThat(pseudonyms.all(a.conversation())).isEmpty();
            assertDomain(() -> pseudonyms.refFor(a.conversation(), Kind.CLIENT, UUID.randomUUID(), "x"),
                    "CONVERSATION_NOT_FOUND", 404);
            assertDomain(() -> drafts.require(a.draft()), "DRAFT_NOT_FOUND", 404);
            assertDomain(() -> inTransaction(tenantB, () -> drafts.requireForUpdate(a.draft())), "DRAFT_NOT_FOUND", 404);
            assertThat(drafts.list(null, null)).isEmpty();
            assertThat(drafts.listByMessage(a.message())).isEmpty();
            assertThat(drafts.markConfirmed(a.draft(), UUID.randomUUID())).isFalse();
            assertThat(drafts.markRejected(a.draft())).isFalse();
            assertThat(drafts.markFailed(a.draft(), "X", "x")).isFalse();
            assertThat(drafts.markExpired(a.draft())).isFalse();
            assertThat(drafts.expireOverdue(clock.instant().plus(Duration.ofDays(30)))).isZero();
            assertThat(usage.usage(period)).isEqualTo(AiUsageStore.Usage.NONE);
            assertThat(settings.get()).isEqualTo(AiSettingsStore.Settings.DEFAULTS);
            assertThat(logs.listLlmCalls(a.conversation())).isEmpty();
            assertThat(logs.listToolCalls(a.conversation())).isEmpty();
            assertThat(conversations.deleteOlderThan(clock.instant().plus(Duration.ofDays(365)))).isZero();
        });

        run(tenantA, () -> {
            assertThat(conversations.require(a.conversation()).locale()).isEqualTo("en");
            assertThat(messages.list(a.conversation())).hasSize(1);
            assertThat(pseudonyms.all(a.conversation())).hasSize(1);
            assertThat(drafts.require(a.draft()).status()).isEqualTo(Status.PENDING);
            assertThat(usage.usage(period).totalTokens()).isEqualTo(120);
            assertThat(settings.get().notesToLlm()).isTrue();
            assertThat(logs.listLlmCalls(a.conversation())).hasSize(1);
            assertThat(logs.listToolCalls(a.conversation())).hasSize(1);
        });
    }

    @Test
    void rlsHidesEveryAiTableOfTenantAFromTenantBInPlainSql() throws Exception {
        writeEverything(tenantA, YearMonth.of(2026, 10));
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, tenantA);
            for (String table : AI_TABLES) {
                assertThat(count(c, "select count(*) from " + table + " where tenant_id = ?", tenantA))
                        .as("%s rows of tenant A, seen by A (positive control)", table).isPositive();
            }
            c.rollback();

            bindTenant(c, tenantB);
            for (String table : AI_TABLES) {
                assertThat(count(c, "select count(*) from " + table + " where tenant_id = ?", tenantA))
                        .as("%s rows of tenant A, seen by B", table).isZero();
            }
            c.rollback();
        }
        // Without any tenant bound nothing is visible either.
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            for (String table : AI_TABLES) {
                assertThat(count(c, "select count(*) from " + table + " where tenant_id = ?", tenantA))
                        .as("%s rows without tenant context", table).isZero();
            }
            c.rollback();
        }
    }

    @Test
    void insertsForAnotherTenantViolateThePolicy() throws Exception {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, tenantB);
            List<String> inserts = List.of(
                    "insert into ai_tenant_settings (tenant_id, notes_to_llm, updated_at) values (?, true, localtimestamp)",
                    "insert into ai_conversation (id, tenant_id, locale, created_at, last_message_at) "
                            + "values (gen_random_uuid(), ?, 'en', localtimestamp, localtimestamp)",
                    "insert into ai_usage_month (tenant_id, period, updated_at) values (?, '2026-10', localtimestamp)",
                    "insert into ai_tool_call (id, tenant_id, conversation_id, tool_name, arguments, status, duration_ms, created_at) "
                            + "values (gen_random_uuid(), ?, gen_random_uuid(), 'get_metrics', '{}', 'OK', 1, localtimestamp)");
            for (String sql : inserts) {
                try (SavepointScope savepoint = new SavepointScope(c); PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setObject(1, tenantA);
                    assertThatThrownBy(ps::executeUpdate)
                            .as(sql)
                            .isInstanceOf(SQLException.class)
                            .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
                    savepoint.rollback();
                }
            }
            c.rollback();
        }
    }

    /**
     * FK checks are not subject to RLS. The composite FK (tenant_id, conversation_id) -> ai_conversation(tenant_id, id)
     * still stops tenant B from hanging its own rows (tenant_id = B, which RLS allows) off tenant A's conversation:
     * with a single-column FK on conversation_id these inserts would succeed.
     */
    @Test
    void childRowsOfAnotherTenantsConversationViolateTheCompositeForeignKey() throws Exception {
        UUID aConversation = as(tenantA, () -> conversations.create("en"));
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, tenantB);
            List<String> inserts = List.of(
                    "insert into ai_message (id, tenant_id, conversation_id, role, content, created_at) "
                            + "values (gen_random_uuid(), ?, ?, 'USER', 'x', localtimestamp)",
                    "insert into ai_pseudonym (tenant_id, conversation_id, kind, token_no, token, entity_id, display, created_at) "
                            + "values (?, ?, 'CLIENT', 1, 'CLIENT_1', gen_random_uuid(), 'x', localtimestamp)",
                    "insert into ai_draft (id, tenant_id, conversation_id, tool_name, payload, status, created_at) "
                            + "values (gen_random_uuid(), ?, ?, 'draft_booking', '{}', 'PENDING', localtimestamp)");
            for (String sql : inserts) {
                try (SavepointScope savepoint = new SavepointScope(c); PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setObject(1, tenantB);
                    ps.setObject(2, aConversation);
                    assertThatThrownBy(ps::executeUpdate)
                            .as(sql)
                            .isInstanceOf(SQLException.class)
                            .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION));
                    savepoint.rollback();
                }
            }
            c.rollback();
        }
        run(tenantB, () -> assertThatThrownBy(() -> drafts.save(aConversation, null, "draft_booking", "{\"v\":1}",
                clock.instant().plus(Duration.ofHours(1))))
                .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo(FOREIGN_KEY_VIOLATION)));
        run(tenantA, () -> {
            assertThat(messages.list(aConversation)).isEmpty();
            assertThat(pseudonyms.all(aConversation)).isEmpty();
            assertThat(drafts.list(aConversation, null)).isEmpty();
        });
    }

    /** Rolls a failed statement back to a savepoint so the surrounding transaction can go on. */
    private static final class SavepointScope implements AutoCloseable {
        private final Connection connection;
        private final Savepoint savepoint;

        SavepointScope(Connection connection) throws SQLException {
            this.connection = connection;
            this.savepoint = connection.setSavepoint();
        }

        void rollback() throws SQLException {
            connection.rollback(savepoint);
        }

        @Override
        public void close() {
        }
    }

    // ------------------------------------------------------------------ conversations and messages

    @Test
    void messagesKeepAppendOrderAndLastNReturnsTheNewestOldestFirst() {
        run(tenantA, () -> {
            UUID conversation = conversations.create("ru");
            UUID m1 = messages.append(conversation, Role.USER, "one", null, null);
            UUID m2 = messages.append(conversation, Role.ASSISTANT, "two", "stub", "stub-1");
            UUID m3 = messages.append(conversation, Role.USER, "three", null, null);

            List<MessageRecord> all = messages.list(conversation);
            assertThat(all).extracting(MessageRecord::id).containsExactly(m1, m2, m3);
            assertThat(all.get(1).role()).isEqualTo(Role.ASSISTANT);
            assertThat(all.get(1).provider()).isEqualTo("stub");
            assertThat(all.get(1).model()).isEqualTo("stub-1");
            assertThat(all.get(0).createdAt()).isBefore(all.get(1).createdAt());
            assertThat(all.get(1).createdAt()).isBefore(all.get(2).createdAt());
            assertThat(messages.lastN(conversation, 2)).extracting(MessageRecord::content).containsExactly("two", "three");
            assertThat(messages.lastN(conversation, 20)).hasSize(3);

            String longText = "x".repeat(AiMessageStore.MAX_CONTENT_CHARS + 100);
            UUID m4 = messages.append(conversation, Role.ASSISTANT, longText, "stub", "stub-1");
            String stored = messages.lastN(conversation, 1).getFirst().content();
            assertThat(messages.lastN(conversation, 1).getFirst().id()).isEqualTo(m4);
            assertThat(stored).hasSize(AiMessageStore.MAX_CONTENT_CHARS).endsWith(AiText.TRUNCATION_MARKER);

            assertDomain(() -> messages.append(UUID.randomUUID(), Role.USER, "x", null, null), "CONVERSATION_NOT_FOUND", 404);
            assertDomain(() -> conversations.require(UUID.randomUUID()), "CONVERSATION_NOT_FOUND", 404);
        });
    }

    /**
     * A fixed clock makes every append compute "previous + 1 µs"; without the conversation lock two concurrent appends
     * would read the same previous time and store equal created_at (random order). The store is built by hand around
     * the application's DSLContext and run in a TransactionTemplate (its @Transactional proxy is not involved).
     */
    @Test
    void concurrentAppendsAreSerialisedIntoAStrictOrder() throws Exception {
        UUID conversation = as(tenantA, () -> conversations.create("en"));
        AiMessageStore fixedClockStore = new JooqAiMessageStore(dsl, Clock.fixed(clock.instant(), ZoneOffset.UTC));
        int threads = 6;
        int appendsPerThread = 10;

        concurrently(threads, () -> {
            for (int i = 0; i < appendsPerThread; i++) {
                inTransaction(tenantA, () -> fixedClockStore.append(conversation, Role.USER, "m", null, null));
            }
            return null;
        });

        List<MessageRecord> all = as(tenantA, () -> messages.list(conversation));
        assertThat(all).hasSize(threads * appendsPerThread);
        assertThat(all).extracting(MessageRecord::id).doesNotHaveDuplicates();
        assertThat(all).extracting(MessageRecord::createdAt).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void touchMovesLastMessageForwardOnly() {
        run(tenantA, () -> {
            UUID conversation = conversations.create("en");
            Instant created = conversations.require(conversation).lastMessageAt();
            Instant later = created.plus(Duration.ofMinutes(5));
            conversations.touch(conversation, later);
            assertThat(conversations.require(conversation).lastMessageAt()).isEqualTo(later);
            conversations.touch(conversation, created);
            assertThat(conversations.require(conversation).lastMessageAt()).isEqualTo(later);
        });
    }

    @Test
    void lockForUpdateNeedsTheCallersTransaction() {
        run(tenantA, () -> {
            UUID conversation = conversations.create("en");
            assertThatThrownBy(() -> conversations.lockForUpdate(conversation))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(inTransaction(tenantA, () -> conversations.lockForUpdate(conversation)).id()).isEqualTo(conversation);
        });
    }

    @Test
    void deletingOldConversationsCascadesToMessagesPseudonymsAndDrafts() {
        run(tenantA, () -> {
            Instant now = clock.instant();
            UUID old = conversations.create("en");
            UUID oldMessage = messages.append(old, Role.USER, "old", null, null);
            pseudonyms.refFor(old, Kind.MASTER, UUID.randomUUID(), "Master");
            UUID oldDraft = drafts.save(old, oldMessage, "draft_cancel", "{\"v\":1}", now.plus(Duration.ofHours(24)));

            UUID recent = conversations.create("en");
            UUID recentMessage = messages.append(recent, Role.USER, "recent", null, null);
            pseudonyms.refFor(recent, Kind.MASTER, UUID.randomUUID(), "Master");
            UUID recentDraft = drafts.save(recent, recentMessage, "draft_cancel", "{\"v\":1}", now.plus(Duration.ofHours(24)));
            conversations.touch(recent, now.plus(Duration.ofHours(2)));

            assertThat(conversations.deleteOlderThan(now.plus(Duration.ofHours(1)))).isEqualTo(1);

            assertDomain(() -> conversations.require(old), "CONVERSATION_NOT_FOUND", 404);
            assertThat(messages.list(old)).isEmpty();
            assertThat(pseudonyms.all(old)).isEmpty();
            assertDomain(() -> drafts.require(oldDraft), "DRAFT_NOT_FOUND", 404);

            assertThat(conversations.require(recent).id()).isEqualTo(recent);
            assertThat(messages.list(recent)).hasSize(1);
            assertThat(pseudonyms.all(recent)).hasSize(1);
            assertThat(drafts.require(recentDraft).status()).isEqualTo(Status.PENDING);
        });
    }

    // ------------------------------------------------------------------ pseudonyms

    @Test
    void pseudonymNumberingIsPerConversationAndKindAndStableForTheSameEntity() {
        run(tenantA, () -> {
            UUID c1 = conversations.create("en");
            UUID c2 = conversations.create("en");
            UUID nino = UUID.randomUUID();
            UUID giorgi = UUID.randomUUID();
            UUID master = UUID.randomUUID();
            UUID visit = UUID.randomUUID();

            assertThat(pseudonyms.refFor(c1, Kind.CLIENT, nino, "Nino")).isEqualTo("CLIENT_1");
            assertThat(pseudonyms.refFor(c1, Kind.CLIENT, giorgi, "Giorgi")).isEqualTo("CLIENT_2");
            assertThat(pseudonyms.refFor(c1, Kind.CLIENT, nino, "Nino renamed")).isEqualTo("CLIENT_1");
            assertThat(pseudonyms.refFor(c1, Kind.MASTER, master, "Ana")).isEqualTo("MASTER_1");
            assertThat(pseudonyms.refFor(c1, Kind.VISIT, visit, "Visit 10:00")).isEqualTo("VISIT_1");
            assertThat(pseudonyms.refFor(c2, Kind.CLIENT, giorgi, "Giorgi")).isEqualTo("CLIENT_1");

            assertThat(pseudonyms.resolve(c1, "CLIENT_2")).contains(new AiPseudonymStore.Ref(Kind.CLIENT, giorgi));
            assertThat(pseudonyms.resolve(c1, "MASTER_1")).contains(new AiPseudonymStore.Ref(Kind.MASTER, master));
            assertThat(pseudonyms.resolve(c1, "CLIENT_9")).isEmpty();
            assertThat(pseudonyms.resolve(c2, "MASTER_1")).as("other conversation").isEmpty();
            assertThat(pseudonyms.resolve(c2, "CLIENT_1")).contains(new AiPseudonymStore.Ref(Kind.CLIENT, giorgi));
            assertThat(pseudonyms.displayOf(c1, "CLIENT_1")).as("display of the first call is kept").contains("Nino");
            assertThat(pseudonyms.all(c1)).extracting(AiPseudonymStore.Mapping::token)
                    .containsExactly("CLIENT_1", "CLIENT_2", "MASTER_1", "VISIT_1");

            assertDomain(() -> pseudonyms.refFor(UUID.randomUUID(), Kind.CLIENT, nino, "Nino"), "CONVERSATION_NOT_FOUND", 404);
        });
    }

    @Test
    void concurrentFirstAppearancesGetDistinctNumbersWithoutGaps() throws Exception {
        UUID conversation = as(tenantA, () -> conversations.create("en"));
        int threads = 8;
        List<UUID> entities = IntStream.range(0, threads).mapToObj(i -> UUID.randomUUID()).toList();
        AtomicInteger next = new AtomicInteger();

        List<String> tokens = concurrently(threads, () -> {
            UUID entity = entities.get(next.getAndIncrement());
            return as(tenantA, () -> pseudonyms.refFor(conversation, Kind.CLIENT, entity, "Client " + entity));
        });

        Set<String> expected = IntStream.rangeClosed(1, threads).mapToObj(i -> "CLIENT_" + i).collect(Collectors.toSet());
        assertThat(tokens).doesNotHaveDuplicates();
        assertThat(Set.copyOf(tokens)).isEqualTo(expected);
        run(tenantA, () -> {
            for (int i = 0; i < threads; i++) {
                String token = tokens.get(i);
                assertThat(pseudonyms.resolve(conversation, token)).isPresent();
            }
            assertThat(pseudonyms.all(conversation)).hasSize(threads);
        });
    }

    /**
     * Review M1: each turn saves a draft (its FK check takes FOR KEY SHARE on the conversation row) and then asks for
     * the draft's ref in the same transaction. With FOR UPDATE in refFor both would wait for the other's KEY SHARE
     * (deadlock, 40P01); FOR NO KEY UPDATE is compatible with KEY SHARE and still serialises the numbering.
     */
    @Test
    void twoTurnsThatSaveADraftAndThenAskForItsRefDoNotDeadlock() throws Exception {
        UUID conversation = as(tenantA, () -> conversations.create("en"));
        CountDownLatch bothSaved = new CountDownLatch(2);

        List<String> tokens = concurrently(2, () -> inTransaction(tenantA, () -> {
            UUID draft = drafts.save(conversation, null, "draft_booking", "{\"v\":1}", clock.instant().plus(Duration.ofHours(1)));
            bothSaved.countDown();
            await(bothSaved);
            String token = pseudonyms.refFor(conversation, Kind.DRAFT, draft, "Draft");
            // The same transaction can still append a message and lock the conversation it already holds.
            messages.append(conversation, Role.ASSISTANT, "made " + token, "stub", "stub-1");
            conversations.lockForUpdate(conversation);
            return token;
        }));

        assertThat(tokens).containsExactlyInAnyOrder("DRAFT_1", "DRAFT_2");
        run(tenantA, () -> {
            assertThat(drafts.list(conversation, null)).hasSize(2);
            assertThat(messages.list(conversation)).hasSize(2);
        });
    }

    @Test
    void concurrentRefForOfTheSameEntityReturnsOneToken() throws Exception {
        UUID conversation = as(tenantA, () -> conversations.create("en"));
        UUID entity = UUID.randomUUID();

        List<String> tokens = concurrently(6, () -> as(tenantA,
                () -> pseudonyms.refFor(conversation, Kind.MASTER, entity, "Ana")));

        assertThat(Set.copyOf(tokens)).containsExactly("MASTER_1");
        run(tenantA, () -> assertThat(pseudonyms.all(conversation)).hasSize(1));
    }

    // ------------------------------------------------------------------ drafts

    @Test
    void conditionalTransitionsChangeOnlyAPendingDraftOnce() {
        run(tenantA, () -> {
            Instant now = clock.instant();
            UUID conversation = conversations.create("en");
            UUID message = messages.append(conversation, Role.USER, "book", null, null);
            UUID booking = drafts.save(conversation, message, "draft_booking", "{\"v\":1,\"type\":\"draft_booking\"}",
                    now.plus(Duration.ofHours(24)));

            DraftRecord pending = drafts.require(booking);
            assertThat(pending.status()).isEqualTo(Status.PENDING);
            assertThat(pending.conversationId()).isEqualTo(conversation);
            assertThat(pending.messageId()).isEqualTo(message);
            assertThat(pending.toolName()).isEqualTo("draft_booking");
            assertThat(pending.payload()).isEqualTo("{\"v\":1,\"type\":\"draft_booking\"}");
            assertThat(pending.expiresAt()).isAfter(now);
            assertThat(pending.resolvedAt()).isNull();

            UUID applied = UUID.randomUUID();
            boolean confirmed = inTransaction(tenantA, () -> {
                assertThat(drafts.requireForUpdate(booking).status()).isEqualTo(Status.PENDING);
                return drafts.markConfirmed(booking, applied);
            });
            assertThat(confirmed).isTrue();
            assertThat(drafts.markConfirmed(booking, UUID.randomUUID())).as("second confirm").isFalse();
            assertThat(drafts.markRejected(booking)).isFalse();
            assertThat(drafts.markFailed(booking, "SLOT_OCCUPIED", "late")).isFalse();
            assertThat(drafts.markExpired(booking)).isFalse();
            DraftRecord done = drafts.require(booking);
            assertThat(done.status()).isEqualTo(Status.CONFIRMED);
            assertThat(done.appliedEntityId()).isEqualTo(applied);
            assertThat(done.confirmedAt()).isNotNull();
            assertThat(done.resolvedAt()).isNotNull();
            assertThat(done.failureCode()).isNull();

            UUID failing = drafts.save(conversation, message, "draft_reschedule", "{\"v\":1}", now.plus(Duration.ofHours(24)));
            assertThat(drafts.markFailed(failing, "DRAFT_STALE", "m".repeat(2000))).isTrue();
            DraftRecord failed = drafts.require(failing);
            assertThat(failed.status()).isEqualTo(Status.FAILED);
            assertThat(failed.failureCode()).isEqualTo("DRAFT_STALE");
            assertThat(failed.failureMessage()).hasSize(AiDraftStore.MAX_FAILURE_MESSAGE_CHARS);
            assertThat(drafts.markConfirmed(failing, UUID.randomUUID())).isFalse();

            UUID rejecting = drafts.save(conversation, message, "draft_cancel", "{\"v\":1}", now.plus(Duration.ofHours(24)));
            assertThat(drafts.markRejected(rejecting)).isTrue();
            assertThat(drafts.markRejected(rejecting)).isFalse();
            assertThat(drafts.require(rejecting).status()).isEqualTo(Status.REJECTED);

            assertThat(drafts.listByMessage(message)).extracting(DraftRecord::id).containsExactly(booking, failing, rejecting);
            assertThat(drafts.list(conversation, Status.REJECTED)).extracting(DraftRecord::id).containsExactly(rejecting);
            assertThat(drafts.list(conversation, null)).extracting(DraftRecord::id).containsExactly(rejecting, failing, booking);

            assertDomain(() -> drafts.require(UUID.randomUUID()), "DRAFT_NOT_FOUND", 404);
            assertThatThrownBy(() -> drafts.requireForUpdate(booking)).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> drafts.save(conversation, message, "draft_booking",
                    "x".repeat(AiDraftStore.MAX_PAYLOAD_CHARS + 1), now)).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void requireForUpdateMakesAConcurrentTransitionWaitAndThenFindTheDraftResolved() throws Exception {
        UUID draft = as(tenantA, () -> {
            UUID conversation = conversations.create("en");
            return drafts.save(conversation, null, "draft_booking", "{\"v\":1}", clock.instant().plus(Duration.ofHours(1)));
        });
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch confirmStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> holder = pool.submit(() -> inTransaction(tenantA, () -> {
                drafts.requireForUpdate(draft);
                locked.countDown();
                try {
                    // Give the competing confirm time to reach the row lock; it must still lose.
                    confirmStarted.await(10, TimeUnit.SECONDS);
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return drafts.markRejected(draft);
            }));
            Future<Boolean> competitor = pool.submit(() -> {
                locked.await(10, TimeUnit.SECONDS);
                confirmStarted.countDown();
                return as(tenantA, () -> drafts.markConfirmed(draft, UUID.randomUUID()));
            });
            assertThat(holder.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(competitor.get(30, TimeUnit.SECONDS)).as("confirm after the locked reject").isFalse();
        } finally {
            pool.shutdownNow();
        }
        run(tenantA, () -> assertThat(drafts.require(draft).status()).isEqualTo(Status.REJECTED));
    }

    @Test
    void overdueDraftsExpireAndFutureOnesStayPending() {
        run(tenantA, () -> {
            Instant now = clock.instant();
            UUID conversation = conversations.create("en");
            UUID overdue = drafts.save(conversation, null, "draft_booking", "{\"v\":1}", now.minus(Duration.ofMinutes(1)));
            UUID fresh = drafts.save(conversation, null, "draft_booking", "{\"v\":1}", now.plus(Duration.ofHours(1)));
            UUID single = drafts.save(conversation, null, "draft_booking", "{\"v\":1}", now.plus(Duration.ofHours(1)));

            assertThat(drafts.expireOverdue(now)).isEqualTo(1);
            assertThat(drafts.require(overdue).status()).isEqualTo(Status.EXPIRED);
            assertThat(drafts.require(overdue).resolvedAt()).isNotNull();
            assertThat(drafts.require(fresh).status()).isEqualTo(Status.PENDING);
            assertThat(drafts.expireOverdue(now)).isZero();

            assertThat(drafts.markExpired(single)).isTrue();
            assertThat(drafts.markExpired(single)).isFalse();
            assertThat(drafts.require(single).status()).isEqualTo(Status.EXPIRED);
        });
    }

    // ------------------------------------------------------------------ budget and settings

    @Test
    void usageIsAdditivePerPeriodAndExceededIsMarkedOnce() {
        YearMonth october = YearMonth.of(2026, 10);
        YearMonth november = YearMonth.of(2026, 11);
        run(tenantA, () -> {
            assertThat(usage.usage(october)).isEqualTo(AiUsageStore.Usage.NONE);
            usage.add(october, 10, 5, 1, 2);
            usage.add(october, 3, 4, 1, 0);
            AiUsageStore.Usage u = usage.usage(october);
            assertThat(u.promptTokens()).isEqualTo(13);
            assertThat(u.completionTokens()).isEqualTo(9);
            assertThat(u.llmCalls()).isEqualTo(2);
            assertThat(u.toolCalls()).isEqualTo(2);
            assertThat(u.totalTokens()).isEqualTo(22);
            assertThat(u.exceededAt()).isNull();
            assertThat(usage.usage(november)).isEqualTo(AiUsageStore.Usage.NONE);

            assertThat(usage.markExceeded(october)).isTrue();
            assertThat(usage.markExceeded(october)).isFalse();
            assertThat(usage.usage(october).exceededAt()).isNotNull();
            assertThat(usage.usage(october).totalTokens()).as("marking keeps the counters").isEqualTo(22);

            assertThat(usage.markExceeded(november)).as("no row yet").isTrue();
            assertThat(usage.markExceeded(november)).isFalse();
            assertThat(usage.usage(november).totalTokens()).isZero();
            assertThat(usage.usage(november).exceededAt()).isNotNull();

            assertThatThrownBy(() -> usage.add(october, -1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void concurrentAddsLoseNoIncrementAndOnlyOneMarkWins() throws Exception {
        YearMonth period = YearMonth.of(2027, 1);
        int threads = 8;
        int addsPerThread = 25;

        concurrently(threads, () -> {
            run(tenantA, () -> {
                for (int i = 0; i < addsPerThread; i++) {
                    usage.add(period, 1, 2, 1, 1);
                }
            });
            return null;
        });
        List<Boolean> marks = concurrently(threads, () -> as(tenantA, () -> usage.markExceeded(period)));

        int total = threads * addsPerThread;
        run(tenantA, () -> {
            AiUsageStore.Usage u = usage.usage(period);
            assertThat(u.promptTokens()).isEqualTo(total);
            assertThat(u.completionTokens()).isEqualTo(2L * total);
            assertThat(u.llmCalls()).isEqualTo(total);
            assertThat(u.toolCalls()).isEqualTo(total);
        });
        assertThat(marks).filteredOn(Boolean::booleanValue).hasSize(1);
    }

    @Test
    void settingsDefaultWithoutARowAndUpsertsKeepTheOtherField() {
        run(tenantA, () -> {
            assertThat(settings.get()).isEqualTo(new AiSettingsStore.Settings(false, null));
            settings.setNotesToLlm(true);
            assertThat(settings.get()).isEqualTo(new AiSettingsStore.Settings(true, null));
            settings.setMonthlyTokenBudget(5000L);
            assertThat(settings.get()).isEqualTo(new AiSettingsStore.Settings(true, 5000L));
            settings.setNotesToLlm(false);
            assertThat(settings.get()).isEqualTo(new AiSettingsStore.Settings(false, 5000L));
            settings.setMonthlyTokenBudget(null);
            assertThat(settings.get()).isEqualTo(new AiSettingsStore.Settings(false, null));
            assertThatThrownBy(() -> settings.setMonthlyTokenBudget(-1L)).isInstanceOf(IllegalArgumentException.class);
        });
    }

    // ------------------------------------------------------------------ logs, hypertable, retention

    @Test
    void llmAndToolCallsAreLoggedWithMetadataAndOverlongTextsAreCut() {
        run(tenantA, () -> {
            UUID conversation = conversations.create("en");
            UUID message = messages.append(conversation, Role.USER, "hi", null, null);
            String prompt = "p".repeat(70_000);
            UUID llmCall = logs.logLlmCall(new AiLogStore.LlmCall(conversation, message, "stub", "stub-1", prompt,
                    "{\"content\":\"hello\"}", 1234, 56, "TOOL_CALLS", 42, "[{\"name\":\"get_metrics\"}]"));
            UUID toolCall = logs.logToolCall(new AiLogStore.ToolCall(conversation, llmCall, "get_metrics",
                    "{\"masterRef\":\"MASTER_1\"}", "r".repeat(20_000), AiLogStore.ToolStatus.ERROR, "TOOL_FAILED", 7));

            List<AiLogStore.LlmCallRecord> calls = logs.listLlmCalls(conversation);
            assertThat(calls).hasSize(1);
            AiLogStore.LlmCallRecord call = calls.getFirst();
            assertThat(call.id()).isEqualTo(llmCall);
            assertThat(call.call().prompt()).hasSize(AiLogStore.MAX_PROMPT_CHARS).endsWith(AiText.TRUNCATION_MARKER);
            assertThat(call.call().response()).isEqualTo("{\"content\":\"hello\"}");
            assertThat(call.call().messageId()).isEqualTo(message);
            assertThat(call.call().promptTokens()).isEqualTo(1234);
            assertThat(call.call().completionTokens()).isEqualTo(56);
            assertThat(call.call().finishReason()).isEqualTo("TOOL_CALLS");
            assertThat(call.call().latencyMs()).isEqualTo(42);
            assertThat(call.call().toolCallsJson()).isEqualTo("[{\"name\":\"get_metrics\"}]");

            List<AiLogStore.ToolCallRecord> tools = logs.listToolCalls(conversation);
            assertThat(tools).hasSize(1);
            AiLogStore.ToolCallRecord tool = tools.getFirst();
            assertThat(tool.id()).isEqualTo(toolCall);
            assertThat(tool.call().llmCallId()).isEqualTo(llmCall);
            assertThat(tool.call().toolName()).isEqualTo("get_metrics");
            assertThat(tool.call().argumentsJson()).isEqualTo("{\"masterRef\":\"MASTER_1\"}");
            assertThat(tool.call().resultJson()).hasSize(AiLogStore.MAX_TOOL_RESULT_CHARS).endsWith(AiText.TRUNCATION_MARKER);
            assertThat(tool.call().status()).isEqualTo(AiLogStore.ToolStatus.ERROR);
            assertThat(tool.call().errorCode()).isEqualTo("TOOL_FAILED");
            assertThat(tool.call().durationMs()).isEqualTo(7);
        });
    }

    /** Review m2: tool name, error code and finish reason come from the LLM/provider and must never fail the insert. */
    @Test
    void llmControlledShortFieldsAreCutToTheirColumns() {
        run(tenantA, () -> {
            UUID conversation = conversations.create("en");
            logs.logLlmCall(new AiLogStore.LlmCall(conversation, null, "p".repeat(100), "m".repeat(300), "[]", null,
                    null, null, "F".repeat(100), null, null));
            logs.logToolCall(new AiLogStore.ToolCall(conversation, null, "t".repeat(300), "{}", null,
                    AiLogStore.ToolStatus.ERROR, "E".repeat(300), 0));

            AiLogStore.LlmCall call = logs.listLlmCalls(conversation).getFirst().call();
            assertThat(call.provider()).hasSize(AiLogStore.MAX_PROVIDER_CHARS).endsWith(AiText.TRUNCATION_MARKER);
            assertThat(call.model()).hasSize(AiLogStore.MAX_MODEL_CHARS).endsWith(AiText.TRUNCATION_MARKER);
            assertThat(call.finishReason()).hasSize(AiLogStore.MAX_FINISH_REASON_CHARS).endsWith(AiText.TRUNCATION_MARKER);
            AiLogStore.ToolCall tool = logs.listToolCalls(conversation).getFirst().call();
            assertThat(tool.toolName()).hasSize(AiLogStore.MAX_TOOL_NAME_CHARS).endsWith(AiText.TRUNCATION_MARKER);
            assertThat(tool.errorCode()).hasSize(AiLogStore.MAX_CODE_CHARS).endsWith(AiText.TRUNCATION_MARKER);

            UUID draft = drafts.save(conversation, null, "draft_booking", "{\"v\":1}", clock.instant().plus(Duration.ofHours(1)));
            assertThat(drafts.markFailed(draft, "C".repeat(200), "x")).isTrue();
            assertThat(drafts.require(draft).failureCode()).hasSize(AiDraftStore.MAX_FAILURE_CODE_CHARS);
        });
    }

    @Test
    void promptAndResponseColumnsWereWidenedOnTheHypertable() throws Exception {
        try (Connection owner = ownerConnection()) {
            for (String column : List.of("prompt", "response")) {
                assertThat(count(owner, """
                        select character_maximum_length from information_schema.columns
                        where table_schema = 'public' and table_name = 'ai_prompt_log' and column_name = ?
                        """, column)).as("ai_prompt_log.%s length", column).isEqualTo(AiLogStore.MAX_PROMPT_CHARS);
            }
        }
    }

    @Test
    void bothLogHypertablesHaveAThirtyDayRetentionPolicy() throws Exception {
        try (Connection owner = ownerConnection(); PreparedStatement ps = owner.prepareStatement("""
                select hypertable_name from timescaledb_information.jobs
                where proc_name = 'policy_retention' and hypertable_schema = 'public'
                  and (config ->> 'drop_after')::interval = interval '30 days' and scheduled
                order by hypertable_name
                """); ResultSet rs = ps.executeQuery()) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            assertThat(names).containsExactly("ai_prompt_log", "ai_tool_call");
        }
    }

    @Test
    void toolCallHypertableKeepsItsChunksInThePrivateSchema() throws Exception {
        as(tenantA, () -> {
            UUID conversation = conversations.create("en");
            return logs.logToolCall(new AiLogStore.ToolCall(conversation, null, "get_schedule", "{}", "{}",
                    AiLogStore.ToolStatus.OK, null, 1));
        });
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, """
                    select count(*) from _timescaledb_catalog.hypertable
                    where schema_name = 'public' and table_name = 'ai_tool_call' and associated_schema_name = 'cadence_chunks'
                    """)).as("ai_tool_call is a hypertable creating chunks in cadence_chunks").isEqualTo(1);
            assertThat(count(owner, "select count(*) from timescaledb_information.chunks where hypertable_name = 'ai_tool_call'"))
                    .as("ai_tool_call has a chunk after an insert").isPositive();
            assertThat(count(owner, """
                    select count(*) from timescaledb_information.chunks
                    where hypertable_name = 'ai_tool_call' and chunk_schema <> 'cadence_chunks'
                    """)).isZero();
        }
    }

    @Test
    void tenantInfoCarriesTheTenantFactsWithoutTheJooqRecord() {
        TenantInfo info = tenants.infoById(tenantA);
        assertThat(info).isEqualTo(new TenantInfo(tenantA, "ai-a-" + tenantA, "AI Studio A", "Asia/Tbilisi", "GEL", "GE"));
        assertThat(info.zone().getId()).isEqualTo("Asia/Tbilisi");
        assertThat(as(tenantB, tenants::currentInfo).id()).isEqualTo(tenantB);
        assertDomain(() -> tenants.infoById(UUID.randomUUID()), "TENANT_NOT_FOUND", 404);
    }
}
