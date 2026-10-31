package com.cadence.app.batch;

import com.cadence.platform.ai.AiConversationStore;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ADR 0002 §7: nightly, per tenant, drops the AI conversations whose last message is older than
 * {@code app.ai.retention} with their messages, pseudonyms and drafts (FK cascade). {@code ai_prompt_log} and
 * {@code ai_tool_call} are not touched here: the Timescale retention policy of V15 drops their chunks.
 */
@Component
public class AiRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AiRetentionJob.class);

    private final TenantDirectory tenants;
    private final AiConversationStore conversations;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration retention;

    public AiRetentionJob(
            TenantDirectory tenants,
            AiConversationStore conversations,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${app.ai.retention:P30D}") Duration retention
    ) {
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("app.ai.retention must be positive: " + retention);
        }
        this.tenants = tenants;
        this.conversations = conversations;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(cron = "${app.ai.retention-cron:0 40 3 * * *}")
    public void run() {
        long startedAt = System.nanoTime();
        Instant cutoff = clock.instant().minus(retention);
        List<TenantRecord> all = tenants.findAll();
        log.info("AI retention started: tenants={}, retention={}, cutoff={}", all.size(), retention, cutoff);
        int deleted = 0;
        int failed = 0;
        for (TenantRecord tenant : all) {
            try {
                deleted += deleteOld(tenant.getId(), cutoff);
            } catch (Exception ex) {
                failed++;
                // One tenant's failure must not stop the others; the next night retries it.
                log.error("AI retention failed for tenant {}", tenant.getId(), ex);
            }
        }
        log.info("AI retention finished: tenants={}, failed={}, conversationsDeleted={}, durationMs={}",
                all.size(), failed, deleted, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private int deleteOld(UUID tenantId, Instant cutoff) {
        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            ActorContext.set(ActorContext.system());
            Integer deleted = transaction.execute(status -> conversations.deleteOlderThan(cutoff));
            log.debug("AI retention for tenant {}: conversationsDeleted={}", tenantId, deleted);
            return deleted == null ? 0 : deleted;
        } finally {
            ActorContext.clear();
        }
    }
}
