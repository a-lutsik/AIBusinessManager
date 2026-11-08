package com.cadence.app.reminder;

import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.VisitNotice;
import com.cadence.notification.api.ClientMessageService;
import com.cadence.notification.api.MessageKind;
import com.cadence.notification.api.VisitMessage;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reminders about confirmed visits: one about a day ahead, one about two hours ahead. Runs only while the Telegram channel
 * is on (with it off nobody is reminded and no owner tasks pile up). Polls like the outbox poller, tenant by tenant:
 * <ul>
 *   <li>a visit starting within two hours gets the short reminder, one starting within 24 hours the day reminder, so a run
 *       that was late (restart) still sends the one that is useful now and never both;</li>
 *   <li>a visit booked inside the reminder's own window gets none: the confirmation just told the client;</li>
 *   <li>cancelled and unconfirmed visits are not selected; a moved visit has a new start, which is part of the idempotency
 *       key of {@link ClientMessageService}, so it is reminded again for the new time and never twice for the same one;</li>
 *   <li>a reminder never becomes a manual owner task: a client without a Telegram chat is not reminded and the owner is not
 *       bothered (only the booking confirmation falls back to a task); the message is delivered by the delivery worker, which
 *       re-checks the visit just before sending;</li>
 *   <li>texts use the tenant's time zone and the client's locale.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
public class VisitReminderJob {

    static final Duration DAY_AHEAD = Duration.ofHours(24);
    static final Duration HOURS_AHEAD = Duration.ofHours(2);

    private static final Logger log = LoggerFactory.getLogger(VisitReminderJob.class);

    private final TenantDirectory tenants;
    private final BookingService bookings;
    private final ClientMessageService clientMessages;
    private final Clock clock;

    public VisitReminderJob(TenantDirectory tenants, BookingService bookings, ClientMessageService clientMessages, Clock clock) {
        this.tenants = tenants;
        this.bookings = bookings;
        this.clientMessages = clientMessages;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.telegram.reminder-poll-ms:60000}")
    public void run() {
        Instant now = clock.instant();
        int sent = 0;
        for (TenantRecord tenant : tenants.findAll()) {
            sent += remindTenant(tenant, now);
        }
        log.debug("Reminder run finished: now={}, sent={}", now, sent);
    }

    /** Returns how many reminders this run sent for the tenant. */
    public int remindTenant(TenantRecord tenant, Instant now) {
        int sent = 0;
        try (TenantContext.Scope ignored = TenantContext.open(tenant.getId())) {
            ActorContext.set(ActorContext.system());
            List<VisitNotice> visits = bookings.confirmedVisitsStartingBetween(now, now.plus(DAY_AHEAD));
            for (VisitNotice visit : visits) {
                MessageKind kind = Duration.between(now, visit.serviceStart()).compareTo(HOURS_AHEAD) <= 0
                        ? MessageKind.REMINDER_HOURS : MessageKind.REMINDER_DAY;
                Duration window = kind == MessageKind.REMINDER_HOURS ? HOURS_AHEAD : DAY_AHEAD;
                if (visit.createdAt().isAfter(visit.serviceStart().minus(window))) {
                    log.debug("Reminder not needed, visit was booked inside its window: visit={}, kind={}", visit.id(), kind);
                    continue;
                }
                try {
                    if (clientMessages.enqueue(new VisitMessage(visit.id(), visit.clientId(), kind, visit.serviceName(), visit.serviceStart(), false))) {
                        sent++;
                    }
                } catch (RuntimeException ex) {
                    log.error("Reminder failed, retried on the next run: tenant={}, visit={}, kind={}", tenant.getId(), visit.id(), kind, ex);
                }
            }
            if (!visits.isEmpty()) {
                log.info("Reminders for tenant {}: candidates={}, sent={}", tenant.getId(), visits.size(), sent);
            }
        } catch (RuntimeException ex) {
            log.error("Reminder run failed for tenant {}", tenant.getId(), ex);
        } finally {
            ActorContext.clear();
        }
        return sent;
    }
}
