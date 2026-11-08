package com.cadence.notification.internal;

import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.CLIENT_CHANNEL_LINK_TOKEN;

/**
 * One-time channel link tokens. Only the SHA-256 hash is stored, as for booking tokens; a token is 32 random bytes in
 * base64url (43 characters, inside Telegram's 64-character limit of the {@code start} parameter).
 */
@Repository
class LinkTokenStore {

    static final String TELEGRAM = "telegram";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Tenant and client behind a live (unused, unexpired) token. */
    record Target(UUID tenantId, UUID clientId) {
    }

    private final DSLContext dsl;

    LinkTokenStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    static String newRaw() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Transactional
    void insert(UUID clientId, String tokenHash, Instant expiresAt) {
        dsl.insertInto(CLIENT_CHANNEL_LINK_TOKEN)
                .set(CLIENT_CHANNEL_LINK_TOKEN.ID, UUID.randomUUID())
                .set(CLIENT_CHANNEL_LINK_TOKEN.TENANT_ID, TenantContext.require())
                .set(CLIENT_CHANNEL_LINK_TOKEN.CLIENT_ID, clientId)
                .set(CLIENT_CHANNEL_LINK_TOKEN.CHANNEL_ID, TELEGRAM)
                .set(CLIENT_CHANNEL_LINK_TOKEN.TOKEN_HASH, tokenHash)
                .set(CLIENT_CHANNEL_LINK_TOKEN.EXPIRES_AT, Utc.toLocal(expiresAt))
                .set(CLIENT_CHANNEL_LINK_TOKEN.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }

    /**
     * Cross-tenant lookup (the webhook has no tenant): RLS hides the row from the runtime role, hence the SECURITY DEFINER
     * function of V17. The caller opens the returned tenant scope before touching tenant data.
     */
    @Transactional(readOnly = true)
    Optional<Target> lookup(String tokenHash) {
        return dsl.resultQuery("select tenant_id, client_id from channel_link_token_lookup({0}, {1})",
                        DSL.val(tokenHash), DSL.val(TELEGRAM))
                .fetchOptional(r -> new Target(r.get(0, UUID.class), r.get(1, UUID.class)));
    }

    /** Marks the token used in the current tenant scope; false when it is already used, expired or unknown. One winner per token. */
    @Transactional
    boolean consume(String tokenHash) {
        Instant now = Instant.now();
        return dsl.update(CLIENT_CHANNEL_LINK_TOKEN)
                .set(CLIENT_CHANNEL_LINK_TOKEN.USED_AT, Utc.toLocal(now))
                .where(TenantAwareDsl.tenantEquals(CLIENT_CHANNEL_LINK_TOKEN.TENANT_ID)
                        .and(CLIENT_CHANNEL_LINK_TOKEN.TOKEN_HASH.eq(tokenHash))
                        .and(CLIENT_CHANNEL_LINK_TOKEN.USED_AT.isNull())
                        .and(CLIENT_CHANNEL_LINK_TOKEN.EXPIRES_AT.gt(Utc.toLocal(now))))
                .execute() == 1;
    }

    @Transactional
    int deleteForClient(UUID clientId) {
        return dsl.deleteFrom(CLIENT_CHANNEL_LINK_TOKEN)
                .where(TenantAwareDsl.tenantEquals(CLIENT_CHANNEL_LINK_TOKEN.TENANT_ID)
                        .and(CLIENT_CHANNEL_LINK_TOKEN.CLIENT_ID.eq(clientId)))
                .execute();
    }
}
