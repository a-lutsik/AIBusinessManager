package com.cadence.notification.internal;

import com.cadence.notification.api.OutboundMessage;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.NOTIFICATION_DELIVERY;

/**
 * Writes {@code notification_delivery}, one row per attempt. The row holds the text that was (or would have been) sent and
 * the client and visit it is about, never the chat id; erasing the client clears the text (see {@link ClientErasure}).
 */
@Component
class DeliveryJournal {

    private static final int MAX_ERROR_CHARS = 2000;

    private final DSLContext dsl;

    DeliveryJournal(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    void record(String channelId, OutboundMessage message, String status, String error) {
        dsl.insertInto(NOTIFICATION_DELIVERY)
                .set(NOTIFICATION_DELIVERY.ID, UUID.randomUUID())
                .set(NOTIFICATION_DELIVERY.TENANT_ID, TenantContext.require())
                .set(NOTIFICATION_DELIVERY.CHANNEL_ID, channelId)
                .set(NOTIFICATION_DELIVERY.TEMPLATE_KEY, message.templateKey())
                .set(NOTIFICATION_DELIVERY.PAYLOAD, message.text())
                .set(NOTIFICATION_DELIVERY.STATUS, status)
                .set(NOTIFICATION_DELIVERY.ERROR, error == null || error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS))
                .set(NOTIFICATION_DELIVERY.CLIENT_ID, message.clientId())
                .set(NOTIFICATION_DELIVERY.APPOINTMENT_ID, message.appointmentId())
                .set(NOTIFICATION_DELIVERY.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }
}
