package com.cadence.notification.internal;

import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Nightly: removes link tokens that were used or expired more than a week ago, and handled queue rows older than
 * {@code app.telegram.dispatch-retention} (cross-tenant, SECURITY DEFINER functions of V17).
 */
@Component
class LinkTokenPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(LinkTokenPurgeJob.class);
    private static final Duration KEEP = Duration.ofDays(7);

    private final DSLContext dsl;

    private final Duration dispatchRetention;

    LinkTokenPurgeJob(DSLContext dsl, @Value("${app.telegram.dispatch-retention:90d}") Duration dispatchRetention) {
        this.dsl = dsl;
        this.dispatchRetention = dispatchRetention;
    }

    @Scheduled(cron = "${app.telegram.token-purge-cron:0 20 3 * * *}")
    @Transactional
    public int purge() {
        Instant now = Instant.now();
        Integer tokens = dsl.resultQuery("select channel_link_token_purge({0})", DSL.val(Utc.toLocal(now.minus(KEEP))))
                .fetchOne(0, Integer.class);
        Integer messages = dsl.resultQuery("select visit_message_purge({0})", DSL.val(Utc.toLocal(now.minus(dispatchRetention))))
                .fetchOne(0, Integer.class);
        log.info("Telegram housekeeping: link tokens removed={}, handled queue rows removed={}", tokens, messages);
        return (tokens == null ? 0 : tokens) + (messages == null ? 0 : messages);
    }
}
