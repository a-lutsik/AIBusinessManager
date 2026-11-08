package com.cadence.notification.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.ClientMessageService;
import com.cadence.notification.api.DeliveryOutcome;
import com.cadence.notification.api.DeliveryOutcome.Status;
import com.cadence.notification.api.OutboundMessage;
import com.cadence.notification.api.VisitMessage;
import com.cadence.platform.i18n.MessageCatalog;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantInfo;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Optional;

import static com.cadence.platform.jooq.Tables.VISIT_MESSAGE_DISPATCH;

@Service
class ClientMessageServiceImpl implements ClientMessageService {

    private static final Logger log = LoggerFactory.getLogger(ClientMessageServiceImpl.class);

    private final DSLContext dsl;
    private final CrmService crm;
    private final TenantDirectory tenants;
    private final MessageCatalog messages;
    private final TelegramNotificationChannel telegram;
    private final ManualTaskNotificationChannel manual;

    ClientMessageServiceImpl(
            DSLContext dsl,
            CrmService crm,
            TenantDirectory tenants,
            MessageCatalog messages,
            TelegramNotificationChannel telegram,
            ManualTaskNotificationChannel manual
    ) {
        this.dsl = dsl;
        this.crm = crm;
        this.tenants = tenants;
        this.messages = messages;
        this.telegram = telegram;
        this.manual = manual;
    }

    @Override
    @Transactional
    public boolean send(VisitMessage message) {
        if (!claim(message)) {
            log.debug("Visit message already sent: visit={}, kind={}, start={}", message.appointmentId(), message.kind(), message.serviceStart());
            return false;
        }
        Optional<ClientView> found = crm.findActiveById(message.clientId());
        if (found.isEmpty()) {
            log.info("Visit message dropped, client is retired: visit={}, kind={}", message.appointmentId(), message.kind());
            return true;
        }
        ClientView client = found.get();
        OutboundMessage outbound = new OutboundMessage(
                client.id(), message.appointmentId(), client.telegramChatId(), message.kind().name(), render(message, client));
        DeliveryOutcome outcome = telegram.send(outbound);
        if (outcome.status() == Status.REJECTED_BY_CLIENT) {
            crm.unlinkTelegram(client.id(), "telegram-forbidden");
        }
        boolean fallback = !outcome.delivered() && message.manualFallback();
        if (fallback) {
            manual.send(outbound);
        }
        log.info("Visit message handled: visit={}, kind={}, telegram={}, manualTask={}",
                message.appointmentId(), message.kind(), outcome.status(), fallback);
        return true;
    }

    /** Takes the idempotency key; false when another run (or an earlier delivery of the same outbox event) holds it. */
    private boolean claim(VisitMessage message) {
        return dsl.insertInto(VISIT_MESSAGE_DISPATCH)
                .set(VISIT_MESSAGE_DISPATCH.TENANT_ID, TenantContext.require())
                .set(VISIT_MESSAGE_DISPATCH.APPOINTMENT_ID, message.appointmentId())
                .set(VISIT_MESSAGE_DISPATCH.KIND, message.kind().name())
                .set(VISIT_MESSAGE_DISPATCH.SERVICE_START, Utc.toLocal(message.serviceStart()))
                .set(VISIT_MESSAGE_DISPATCH.CREATED_AT, Utc.toLocal(Instant.now()))
                .onConflictDoNothing()
                .execute() == 1;
    }

    /** The text in the client's language, with the visit time in the tenant's time zone. */
    private String render(VisitMessage message, ClientView client) {
        TenantInfo tenant = tenants.currentInfo();
        Locale locale = MessageCatalog.localeOrDefault(client.preferredLocale());
        ZoneId zone = tenant.zone();
        return messages.forClient(
                locale.getLanguage(),
                message.kind().messageKey(),
                message.serviceName(),
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(message.serviceStart().atZone(zone)),
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(message.serviceStart().atZone(zone)),
                tenant.displayName()
        );
    }
}
