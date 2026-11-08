package com.cadence.notification.internal;

import com.cadence.crm.api.ClientRetirementListener;
import com.cadence.platform.persistence.TenantAwareDsl;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static com.cadence.platform.jooq.Tables.NOTIFICATION_DELIVERY;

/**
 * Forgets what the channel layer holds about a retired client: open link tokens always, and on erasure also the message
 * texts of the delivery journal (the rows stay as facts: channel, status, time).
 */
@Component
class ClientErasure implements ClientRetirementListener {

    private static final Logger log = LoggerFactory.getLogger(ClientErasure.class);

    private final LinkTokenStore tokens;
    private final DSLContext dsl;

    ClientErasure(LinkTokenStore tokens, DSLContext dsl) {
        this.tokens = tokens;
        this.dsl = dsl;
    }

    @Override
    public void clientRetiring(UUID clientId) {
        int removed = tokens.deleteForClient(clientId);
        log.debug("Channel link tokens removed for a retiring client: client={}, count={}", clientId, removed);
    }

    @Override
    public void clientErasing(UUID clientId) {
        clientRetiring(clientId);
        int cleared = dsl.update(NOTIFICATION_DELIVERY)
                .set(NOTIFICATION_DELIVERY.PAYLOAD, (String) null)
                .set(NOTIFICATION_DELIVERY.ERROR, (String) null)
                .where(TenantAwareDsl.tenantEquals(NOTIFICATION_DELIVERY.TENANT_ID)
                        .and(NOTIFICATION_DELIVERY.CLIENT_ID.eq(clientId)))
                .execute();
        log.info("Delivery journal texts erased: client={}, rows={}", clientId, cleared);
    }
}
