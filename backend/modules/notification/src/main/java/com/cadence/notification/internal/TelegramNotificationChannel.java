package com.cadence.notification.internal;

import com.cadence.notification.api.NotificationChannel;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.NOTIFICATION_DELIVERY;

/**
 * Telegram adapter stub (phase 4). Without chat_id / bot token, delivery is recorded as skipped.
 */
@Component
public class TelegramNotificationChannel implements NotificationChannel {

    private final DSLContext dsl;

    public TelegramNotificationChannel(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public String channelId() {
        return "telegram";
    }

    @Override
    public void send(String tenantScopedPayloadJson) {
        dsl.insertInto(NOTIFICATION_DELIVERY)
                .set(NOTIFICATION_DELIVERY.ID, UUID.randomUUID())
                .set(NOTIFICATION_DELIVERY.TENANT_ID, TenantContext.require())
                .set(NOTIFICATION_DELIVERY.CHANNEL_ID, channelId())
                .set(NOTIFICATION_DELIVERY.TEMPLATE_KEY, "telegram_stub")
                .set(NOTIFICATION_DELIVERY.PAYLOAD, tenantScopedPayloadJson)
                .set(NOTIFICATION_DELIVERY.STATUS, "STUBBED")
                .set(NOTIFICATION_DELIVERY.ERROR, "Telegram bot is not configured; use manual fallback")
                .set(NOTIFICATION_DELIVERY.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }
}
