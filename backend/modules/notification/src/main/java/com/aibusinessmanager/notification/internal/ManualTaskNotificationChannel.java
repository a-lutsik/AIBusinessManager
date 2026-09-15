package com.aibusinessmanager.notification.internal;

import com.aibusinessmanager.notification.api.NotificationChannel;
import com.aibusinessmanager.notification.api.OwnerTaskService;
import com.aibusinessmanager.platform.tenancy.TenantContext;
import com.aibusinessmanager.platform.time.Utc;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

import static com.aibusinessmanager.platform.jooq.Tables.NOTIFICATION_DELIVERY;

/** Manual-fallback channel: creates an owner task with ready-made text. */
@Component
public class ManualTaskNotificationChannel implements NotificationChannel {

    private final OwnerTaskService ownerTaskService;
    private final DSLContext dsl;

    public ManualTaskNotificationChannel(OwnerTaskService ownerTaskService, DSLContext dsl) {
        this.ownerTaskService = ownerTaskService;
        this.dsl = dsl;
    }

    @Override
    public String channelId() {
        return "manual";
    }

    @Override
    public void send(String tenantScopedPayloadJson) {
        ownerTaskService.create(
                "MANUAL_MESSAGE",
                "Send message to client",
                tenantScopedPayloadJson,
                tenantScopedPayloadJson,
                null,
                null,
                null
        );
        dsl.insertInto(NOTIFICATION_DELIVERY)
                .set(NOTIFICATION_DELIVERY.ID, UUID.randomUUID())
                .set(NOTIFICATION_DELIVERY.TENANT_ID, TenantContext.require())
                .set(NOTIFICATION_DELIVERY.CHANNEL_ID, channelId())
                .set(NOTIFICATION_DELIVERY.TEMPLATE_KEY, "manual")
                .set(NOTIFICATION_DELIVERY.PAYLOAD, tenantScopedPayloadJson)
                .set(NOTIFICATION_DELIVERY.STATUS, "QUEUED_MANUAL")
                .set(NOTIFICATION_DELIVERY.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }
}
