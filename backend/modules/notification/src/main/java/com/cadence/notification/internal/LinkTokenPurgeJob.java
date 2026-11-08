package com.cadence.notification.internal;

import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/** Nightly: removes link tokens that were used or expired more than a week ago (cross-tenant, SECURITY DEFINER function of V17). */
@Component
class LinkTokenPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(LinkTokenPurgeJob.class);
    private static final Duration KEEP = Duration.ofDays(7);

    private final DSLContext dsl;

    LinkTokenPurgeJob(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Scheduled(cron = "${app.telegram.token-purge-cron:0 20 3 * * *}")
    @Transactional
    public int purge() {
        Integer removed = dsl.resultQuery("select channel_link_token_purge({0})", DSL.val(Utc.toLocal(Instant.now().minus(KEEP))))
                .fetchOne(0, Integer.class);
        log.info("Channel link tokens purged: removed={}", removed);
        return removed == null ? 0 : removed;
    }
}
