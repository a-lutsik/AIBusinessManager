package com.aibusinessmanager.platform.outbox;

import com.aibusinessmanager.platform.tenancy.ActorContext;
import com.aibusinessmanager.platform.tenancy.TenantContext;
import com.aibusinessmanager.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.aibusinessmanager.platform.jooq.Tables.OUTBOX_EVENT;

@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final DSLContext dsl;
    private final Map<String, OutboxHandler> handlers;

    public OutboxPoller(DSLContext dsl, ObjectProvider<OutboxHandler> handlers) {
        this.dsl = dsl;
        this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::type, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:2000}")
    @Transactional
    public void poll() {
        var batch = dsl.selectFrom(OUTBOX_EVENT)
                .where(OUTBOX_EVENT.PROCESSED_AT.isNull())
                .orderBy(OUTBOX_EVENT.CREATED_AT.asc())
                .limit(25)
                .forUpdate()
                .skipLocked()
                .fetch();
        for (var event : batch) {
            UUID tenantId = event.get(OUTBOX_EVENT.TENANT_ID);
            String type = event.get(OUTBOX_EVENT.TYPE);
            UUID id = event.get(OUTBOX_EVENT.ID);
            try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
                ActorContext.set(ActorContext.system());
                OutboxHandler handler = handlers.get(type);
                if (handler != null) {
                    handler.handle(event);
                }
                dsl.update(OUTBOX_EVENT)
                        .set(OUTBOX_EVENT.PROCESSED_AT, Utc.toLocal(Instant.now()))
                        .set(OUTBOX_EVENT.LAST_ERROR, (String) null)
                        .where(OUTBOX_EVENT.ID.eq(id))
                        .execute();
            } catch (Exception ex) {
                log.warn("Outbox handler failed for {} {}", type, id, ex);
                dsl.update(OUTBOX_EVENT)
                        .set(OUTBOX_EVENT.ATTEMPTS, OUTBOX_EVENT.ATTEMPTS.plus(1))
                        .set(OUTBOX_EVENT.LAST_ERROR, trim(ex.getMessage()))
                        .where(OUTBOX_EVENT.ID.eq(id))
                        .execute();
            } finally {
                ActorContext.clear();
            }
        }
    }

    private static String trim(String message) {
        if (message == null) {
            return "error";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
