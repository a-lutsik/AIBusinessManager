package com.cadence.notification.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.DeliveryOutcome;
import com.cadence.notification.api.DeliveryOutcome.Status;
import com.cadence.notification.api.DeliveryRunner;
import com.cadence.notification.api.OutboundMessage;
import com.cadence.notification.api.VisitGuard;
import com.cadence.platform.i18n.MessageCatalog;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.platform.tenant.TenantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Delivers the queued visit messages. Per message, three steps: a short transaction that re-checks the visit
 * ({@link VisitGuard}) and prepares the text; the provider call with no transaction open; a short transaction that journals
 * the outcome, drops a dead link (HTTP 403, only that chat), adds the manual task when asked for and marks the message done.
 *
 * <p>Restarts: a message in flight when the process died is claimed again after {@code STALE_AFTER}, flagged stale, and is
 * never sent a second time (whether Telegram got it is unknown): it is journaled FAILED and falls back like any failure. A
 * shutdown before the call, or while waiting after an explicit refusal, puts the message back in the queue; a shutdown inside the call
 * leaves it SENDING (outcome unknown, so it is settled as stale and never sent twice). A row is marked SENDING one at a time, right
 * before its own call, so a crash puts at most one message in doubt. Runs on its own scheduler thread
 * ({@code spring.task.scheduling.pool.size} is above one), so a slow provider never delays the outbox or the reminders.
 */
@Component
class MessageDeliveryWorker implements DeliveryRunner {

    static final int BATCH_SIZE = 20;
    static final Duration STALE_AFTER = Duration.ofMinutes(5);
    /** Preparation attempts per message (a broken tenant setting, a missing catalog key, a lock timeout); then it is closed as FAILED. */
    static final int MAX_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(MessageDeliveryWorker.class);

    private final DispatchQueue queue;
    private final VisitGuard guard;
    private final CrmService crm;
    private final TenantDirectory tenants;
    private final MessageCatalog messages;
    private final TelegramNotificationChannel telegram;
    private final ManualTaskNotificationChannel manual;
    private final DeliveryJournal journal;
    private final TransactionTemplate tx;
    private final boolean pollEnabled;
    private volatile boolean stopping;

    MessageDeliveryWorker(
            DispatchQueue queue,
            VisitGuard guard,
            CrmService crm,
            TenantDirectory tenants,
            MessageCatalog messages,
            TelegramNotificationChannel telegram,
            ManualTaskNotificationChannel manual,
            DeliveryJournal journal,
            PlatformTransactionManager transactionManager,
            @Value("${app.delivery.enabled:true}") boolean pollEnabled
    ) {
        this.queue = queue;
        this.guard = guard;
        this.crm = crm;
        this.tenants = tenants;
        this.messages = messages;
        this.telegram = telegram;
        this.manual = manual;
        this.journal = journal;
        this.tx = new TransactionTemplate(transactionManager);
        this.pollEnabled = pollEnabled;
    }

    @Scheduled(fixedDelayString = "${app.delivery.poll-ms:2000}")
    void poll() {
        if (pollEnabled) {
            runOnce();
        }
    }

    /** A graceful stop must not start new rows: the call in flight finishes (see the shutdown settings), nothing else is claimed. */
    @EventListener(ContextClosedEvent.class)
    void onContextClosing() {
        stopping = true;
    }

    @Override
    public int runOnce() {
        int handled = 0;
        while (handled < BATCH_SIZE && !stopping) {
            // One row at a time: a row is marked SENDING only when its own turn comes, so a crash leaves at most one in doubt.
            List<DispatchQueue.Claimed> claimed = tx.execute(status -> queue.claim(1, Instant.now().minus(STALE_AFTER)));
            if (claimed == null || claimed.isEmpty()) {
                break;
            }
            handled++;
            boolean goOn = false;
            try (TenantContext.Scope ignored = TenantContext.open(claimed.get(0).tenantId())) {
                ActorContext.set(ActorContext.system());
                goOn = deliver(claimed.get(0));
            } catch (RuntimeException ex) {
                // Unexpected, after the call or in the bookkeeping: the row stays SENDING and is settled as stale later.
                log.error("Delivery bookkeeping failed, the message is settled as stale later: tenant={}, visit={}, kind={}",
                        claimed.get(0).tenantId(), claimed.get(0).appointmentId(), claimed.get(0).kind(), ex);
                goOn = true;
            } finally {
                ActorContext.clear();
            }
            if (!goOn) {
                break;
            }
        }
        if (handled > 0) {
            log.debug("Delivery pass finished: handled={}", handled);
        }
        return handled;
    }

    private record Plan(OutboundMessage message) {
    }

    /** Returns false when the pass must stop (interrupted, or the message went back to the queue). */
    private boolean deliver(DispatchQueue.Claimed claimed) {
        Plan plan;
        try {
            plan = tx.execute(status -> prepare(claimed));
        } catch (RuntimeException ex) {
            prepareFailed(claimed, ex);
            return true;
        }
        if (plan == null) {
            tx.executeWithoutResult(status -> queue.done(claimed));
            return true;
        }
        DeliveryOutcome outcome = claimed.stale()
                ? new DeliveryOutcome(Status.FAILED, "Delivery state unknown after a restart; not sent again")
                : telegram.send(plan.message());
        if (outcome.status() == Status.INTERRUPTED) {
            tx.executeWithoutResult(status -> queue.release(claimed));
            log.info("Delivery interrupted before anything was sent, message back in the queue: visit={}, kind={}", claimed.appointmentId(), claimed.kind());
            return false;
        }
        if (outcome.status() == Status.UNKNOWN) {
            // Left SENDING on purpose: whether Telegram got it is unknown, so it is settled as stale (never resent).
            log.warn("Delivery interrupted inside the call, outcome unknown, not resent: visit={}, kind={}", claimed.appointmentId(), claimed.kind());
            return false;
        }
        tx.executeWithoutResult(status -> finish(claimed, plan.message(), outcome));
        return true;
    }

    /**
     * The message could not be prepared. A stale row is closed (it must never be sent). Otherwise it goes back with a growing
     * delay, so the rest of the queue keeps moving; after {@link #MAX_ATTEMPTS} it is closed as FAILED, with a manual task for a
     * message that asks for one (the owner's generic text: the client text could not be built).
     */
    private void prepareFailed(DispatchQueue.Claimed claimed, RuntimeException ex) {
        int attempt = claimed.attempts() + 1;
        if (claimed.stale() || attempt >= MAX_ATTEMPTS) {
            log.error("Visit message given up after {} attempts: tenant={}, visit={}, kind={}",
                    attempt, claimed.tenantId(), claimed.appointmentId(), claimed.kind(), ex);
            tx.executeWithoutResult(status -> {
                OutboundMessage message = new OutboundMessage(claimed.clientId(), claimed.appointmentId(), null,
                        claimed.kind().name(), messages.forOwner("owner.task.messageFailed.body"));
                if (!claimed.stale()) {
                    journal.record(telegram.channelId(), message, "FAILED", "Could not be prepared: " + ex.getClass().getSimpleName());
                    if (claimed.manualFallback()) {
                        manual.send(message);
                        journal.record(manual.channelId(), message, "QUEUED_MANUAL", null);
                    }
                }
                queue.done(claimed);
            });
            return;
        }
        Duration delay = Duration.ofMinutes(Math.min(30, 1L << attempt));
        log.warn("Visit message could not be prepared, attempt {} of {}, next in {}: tenant={}, visit={}, kind={}, cause={}",
                attempt, MAX_ATTEMPTS, delay, claimed.tenantId(), claimed.appointmentId(), claimed.kind(), ex.toString());
        tx.executeWithoutResult(status -> queue.retryLater(claimed, delay));
    }

    /** Null: the message is no longer wanted (visit cancelled or moved, client retired). */
    private Plan prepare(DispatchQueue.Claimed claimed) {
        if (!guard.stillDeliverable(claimed.appointmentId(), claimed.kind(), claimed.serviceStart())) {
            log.info("Visit message dropped, the visit changed: visit={}, kind={}", claimed.appointmentId(), claimed.kind());
            return null;
        }
        Optional<ClientView> found = crm.findActiveById(claimed.clientId());
        if (found.isEmpty()) {
            log.info("Visit message dropped, client is retired: visit={}, kind={}", claimed.appointmentId(), claimed.kind());
            return null;
        }
        ClientView client = found.get();
        return new Plan(new OutboundMessage(client.id(), claimed.appointmentId(), client.telegramChatId(),
                claimed.kind().name(), render(claimed, client)));
    }

    private void finish(DispatchQueue.Claimed claimed, OutboundMessage message, DeliveryOutcome outcome) {
        journal.record(telegram.channelId(), message, outcome.status() == Status.REJECTED_BY_CLIENT ? "FAILED" : outcome.status().name(),
                outcome.error());
        if (outcome.status() == Status.REJECTED_BY_CLIENT) {
            crm.unlinkTelegram(message.clientId(), message.destination(), "telegram-forbidden");
        }
        boolean fallback = !outcome.delivered() && claimed.manualFallback();
        if (fallback) {
            manual.send(message);
            journal.record(manual.channelId(), message, "QUEUED_MANUAL", null);
        }
        queue.done(claimed);
        log.info("Visit message handled: visit={}, kind={}, telegram={}, manualTask={}",
                claimed.appointmentId(), claimed.kind(), outcome.status(), fallback);
    }

    /** The text in the client's language, with the visit time in the tenant's time zone. */
    private String render(DispatchQueue.Claimed claimed, ClientView client) {
        TenantInfo tenant = tenants.currentInfo();
        Locale locale = MessageCatalog.localeOrDefault(client.preferredLocale());
        ZoneId zone = tenant.zone();
        return messages.forClient(
                locale.getLanguage(),
                claimed.kind().messageKey(),
                claimed.serviceName(),
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(claimed.serviceStart().atZone(zone)),
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(claimed.serviceStart().atZone(zone)),
                tenant.displayName()
        );
    }
}
