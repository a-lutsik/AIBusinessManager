package com.cadence.app;

import com.cadence.app.batch.AiRetentionJob;
import com.cadence.platform.ai.AiConversationStore;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.ai.AiMessageStore;
import com.cadence.platform.ai.AiMessageStore.Role;
import com.cadence.platform.ai.AiPseudonymStore;
import com.cadence.platform.ai.AiPseudonymStore.Kind;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0002 §7: the nightly {@link AiRetentionJob} against the real schema (default {@code app.ai.retention} of 30 days).
 * Conversations are aged through the owner connection, since the stores only move {@code last_message_at} forward.
 */
@SpringBootTest
class AiRetentionJobIT extends AbstractTimescaleIT {

    @Autowired
    AiRetentionJob job;
    @Autowired
    AiConversationStore conversations;
    @Autowired
    AiMessageStore messages;
    @Autowired
    AiPseudonymStore pseudonyms;
    @Autowired
    AiDraftStore drafts;
    @Autowired
    TenantDirectory tenants;
    @Autowired
    Clock clock;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void freshTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        tenants.upsert(tenantA, "ret-a-" + tenantA, "Retention A", "Asia/Tbilisi", "GEL", "GE");
        tenants.upsert(tenantB, "ret-b-" + tenantB, "Retention B", "Europe/Prague", "CZK", "CZ");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** A conversation with a message, a pseudonym and a draft, idle for {@code idle}. */
    private UUID conversation(UUID tenant, Duration idle) throws SQLException {
        UUID id = in(tenant, () -> {
            UUID conversation = conversations.create("en");
            UUID message = messages.append(conversation, Role.USER, "hello", null, null);
            pseudonyms.refFor(conversation, Kind.MASTER, UUID.randomUUID(), "Master");
            drafts.save(conversation, message, "draft_cancel", "{\"v\":1}", clock.instant().plus(Duration.ofHours(24)));
            return conversation;
        });
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             PreparedStatement ps = c.prepareStatement("update ai_conversation set last_message_at = ? where id = ?")) {
            ps.setObject(1, LocalDateTime.ofInstant(clock.instant().minus(idle), ZoneOffset.UTC));
            ps.setObject(2, id);
            ps.executeUpdate();
        }
        return id;
    }

    private static <T> T in(UUID tenant, Supplier<T> call) {
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            return call.get();
        }
    }

    /** Rows of the conversation in the four tables, counted as the owner (all tenants, no RLS). */
    private static long rows(UUID conversation) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             PreparedStatement ps = c.prepareStatement("""
                     select (select count(*) from ai_conversation where id = ?)
                          + (select count(*) from ai_message where conversation_id = ?)
                          + (select count(*) from ai_pseudonym where conversation_id = ?)
                          + (select count(*) from ai_draft where conversation_id = ?)""")) {
            for (int i = 1; i <= 4; i++) {
                ps.setObject(i, conversation);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void dropsOldConversationsWithTheirRowsInEveryTenantAndKeepsFreshOnes() throws SQLException {
        UUID oldA = conversation(tenantA, Duration.ofDays(31));
        UUID freshA = conversation(tenantA, Duration.ofDays(29));
        UUID oldB = conversation(tenantB, Duration.ofDays(45));
        UUID freshB = conversation(tenantB, Duration.ZERO);
        assertThat(rows(oldA)).isEqualTo(4);
        assertThat(rows(oldB)).isEqualTo(4);

        job.run();

        assertThat(rows(oldA)).isZero();
        assertThat(rows(oldB)).isZero();
        assertThat(rows(freshA)).isEqualTo(4);
        assertThat(rows(freshB)).isEqualTo(4);
    }

    @Test
    void aSecondRunWithNothingOldChangesNothing() throws SQLException {
        UUID fresh = conversation(tenantA, Duration.ofDays(1));

        job.run();
        job.run();

        assertThat(rows(fresh)).isEqualTo(4);
    }
}
