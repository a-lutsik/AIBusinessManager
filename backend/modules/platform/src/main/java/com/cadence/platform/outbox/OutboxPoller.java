package com.cadence.platform.outbox;

import com.cadence.platform.jooq.tables.records.OutboxEventRecord;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.cadence.platform.jooq.Tables.OUTBOX_EVENT;

/**
 * Delivers outbox events at least once.
 *
 * <p>One outer transaction per poll claims a batch across tenants ({@code outbox_claim}, SECURITY DEFINER,
 * {@code FOR UPDATE SKIP LOCKED}: the row locks live in this outer transaction). Each event then runs under its own
 * tenant scope in a nested transaction (savepoint): handler side effects and {@code processed_at} commit together,
 * or the savepoint is rolled back and the failure ({@code attempts + 1}, {@code last_error}) is recorded in the outer
 * transaction. A failing event therefore never blocks later events, and after {@code app.outbox.max-attempts} it is
 * no longer claimed.
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    static final int BATCH_SIZE = 25;

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final DSLContext dsl;
    private final Map<String, OutboxHandler> handlers;
    private final TransactionTemplate perEvent;
    private final int maxAttempts;

    public OutboxPoller(
            DSLContext dsl,
            ObjectProvider<OutboxHandler> handlers,
            PlatformTransactionManager transactionManager,
            @Value("${app.outbox.max-attempts:10}") int maxAttempts
    ) {
        this.dsl = dsl;
        this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::type, Function.identity()));
        this.perEvent = new TransactionTemplate(transactionManager);
        this.perEvent.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:2000}")
    @Transactional
    public void poll() {
        for (OutboxEventRecord event : claim()) {
            process(event);
        }
    }

    private List<OutboxEventRecord> claim() {
        return dsl.resultQuery("select * from outbox_claim({0}, {1})", DSL.val(BATCH_SIZE), DSL.val(maxAttempts))
                .fetchInto(OUTBOX_EVENT);
    }

    private void process(OutboxEventRecord event) {
        UUID id = event.getId();
        String type = event.getType();
        try (TenantContext.Scope ignored = TenantContext.open(event.getTenantId())) {
            ActorContext.set(ActorContext.system());
            try {
                perEvent.executeWithoutResult(status -> {
                    OutboxHandler handler = handlers.get(type);
                    if (handler != null) {
                        handler.handle(event);
                    }
                    dsl.update(OUTBOX_EVENT)
                            .set(OUTBOX_EVENT.PROCESSED_AT, Utc.toLocal(Instant.now()))
                            .set(OUTBOX_EVENT.LAST_ERROR, (String) null)
                            .where(OUTBOX_EVENT.ID.eq(id))
                            .execute();
                });
            } catch (VirtualMachineError fatal) {
                // OutOfMemoryError and friends: the JVM is not in a state to keep delivering; do not swallow.
                throw fatal;
            } catch (Exception | Error ex) {
                // Any other failure of this one event, an Error from a handler included (a linkage or assertion
                // error must not roll back the claim of the whole batch). The savepoint is already rolled back;
                // record the failure in the outer transaction, same tenant scope.
                log.warn("Outbox handler failed for {} {} (attempt {} of {})",
                        type, id, event.getAttempts() + 1, maxAttempts, ex);
                dsl.update(OUTBOX_EVENT)
                        .set(OUTBOX_EVENT.ATTEMPTS, OUTBOX_EVENT.ATTEMPTS.plus(1))
                        .set(OUTBOX_EVENT.LAST_ERROR, trim(ex.getMessage()))
                        .where(OUTBOX_EVENT.ID.eq(id))
                        .execute();
            }
        } finally {
            ActorContext.clear();
        }
    }

    private static String trim(String message) {
        if (message == null) {
            return "error";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
