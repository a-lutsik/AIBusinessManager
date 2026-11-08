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
 * shutdown during the call puts the message back in the queue untouched. Runs on its own scheduler thread
 * ({@code spring.task.scheduling.pool.size} is above one), so a slow provider never delays the outbox or the reminders.
 */
@Component
class MessageDeliveryWorker implements DeliveryRunner {

    static final int BATCH_SIZE = 20;
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

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

    @Override
    public int runOnce() {
        List<DispatchQueue.Claimed> batch = tx.execute(status -> queue.claim(BATCH_SIZE, Instant.now().minus(STALE_AFTER)));
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        log.debug("Delivery batch claimed: size={}", batch.size());
        for (DispatchQueue.Claimed claimed : batch) {
            try (TenantContext.Scope ignored = TenantContext.open(claimed.tenantId())) {
                ActorContext.set(ActorContext.system());
                deliver(claimed);
            } catch (RuntimeException ex) {
                log.error("Delivery failed, the message is retried or settled as stale: tenant={}, visit={}, kind={}",
                        claimed.tenantId(), claimed.appointmentId(), claimed.kind(), ex);
            } finally {
                ActorContext.clear();
            }
        }
        return batch.size();
    }

    private record Plan(OutboundMessage message) {
    }

    private void deliver(DispatchQueue.Claimed claimed) {
        Plan plan;
        try {
            plan = tx.execute(status -> prepare(claimed));
        } catch (RuntimeException ex) {
            tx.executeWithoutResult(status -> queue.release(claimed));
            throw ex;
        }
        if (plan == null) {
            tx.executeWithoutResult(status -> queue.done(claimed));
            return;
        }
        DeliveryOutcome outcome = claimed.stale()
                ? new DeliveryOutcome(Status.FAILED, "Delivery state unknown after a restart; not sent again")
                : telegram.send(plan.message());
        if (outcome.status() == Status.INTERRUPTED) {
            tx.executeWithoutResult(status -> queue.release(claimed));
            log.info("Delivery interrupted, message stays queued: visit={}, kind={}", claimed.appointmentId(), claimed.kind());
            return;
        }
        tx.executeWithoutResult(status -> finish(claimed, plan.message(), outcome));
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
