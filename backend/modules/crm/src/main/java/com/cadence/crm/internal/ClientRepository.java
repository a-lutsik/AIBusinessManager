package com.cadence.crm.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.CLIENT;

@Repository
class ClientRepository {

    private final DSLContext dsl;

    ClientRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    Optional<ClientView> findById(UUID id) {
        return dsl.selectFrom(CLIENT)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .fetchOptional(this::toView);
    }

    Optional<ClientView> findByPhone(String e164) {
        return dsl.selectFrom(CLIENT)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.NORMALIZED_PHONE.eq(e164)))
                .fetchOptional(this::toView);
    }

    List<ClientView> search(String query) {
        var condition = TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ANONYMIZED_AT.isNull());
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim() + "%";
            condition = condition.and(CLIENT.DISPLAY_NAME.likeIgnoreCase(like)
                    .or(CLIENT.NORMALIZED_PHONE.like("%" + query.trim() + "%")));
        }
        return dsl.selectFrom(CLIENT)
                .where(condition)
                .orderBy(CLIENT.LAST_VISIT_AT.desc().nullsLast(), CLIENT.DISPLAY_NAME.asc())
                .limit(100)
                .fetch(this::toView);
    }

    ClientView insert(ClientView client) {
        UUID tenantId = TenantContext.require();
        dsl.insertInto(CLIENT)
                .set(CLIENT.ID, client.id())
                .set(CLIENT.TENANT_ID, tenantId)
                .set(CLIENT.NORMALIZED_PHONE, client.normalizedPhone())
                .set(CLIENT.DISPLAY_NAME, client.displayName())
                .set(CLIENT.NOTES, client.notes())
                .set(CLIENT.TAGS, client.tags())
                .set(CLIENT.PREFERRED_LOCALE, client.preferredLocale())
                .set(CLIENT.TELEGRAM_CHAT_ID, client.telegramChatId())
                .set(CLIENT.MARKETING_CONSENT, client.marketingConsent())
                .set(CLIENT.CREATED_AT, Utc.toLocal(Instant.now()))
                .set(CLIENT.COMPLETED_VISIT_COUNT, client.completedVisitCount())
                .set(CLIENT.NO_SHOW_COUNT, client.noShowCount())
                .set(CLIENT.LATE_CANCEL_COUNT, client.lateCancelCount())
                .set(CLIENT.COMPLETED_VALUE_MINOR, client.completedValueMinor())
                .execute();
        return client;
    }

    void updateProfile(UUID id, String name, String notes, String tags, String locale, Boolean consent, String consentVersion) {
        var update = dsl.update(CLIENT)
                .set(CLIENT.DISPLAY_NAME, name);
        if (notes != null) {
            update = update.set(CLIENT.NOTES, notes);
        }
        if (tags != null) {
            update = update.set(CLIENT.TAGS, tags);
        }
        if (locale != null) {
            update = update.set(CLIENT.PREFERRED_LOCALE, locale);
        }
        if (consent != null) {
            update = update.set(CLIENT.MARKETING_CONSENT, consent)
                    .set(CLIENT.CONSENT_VERSION, consentVersion)
                    .set(CLIENT.CONSENT_AT, Utc.toLocal(Instant.now()));
        }
        update.where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id))).execute();
    }

    void bumpCompleted(UUID id, Instant when, int valueMinor) {
        dsl.update(CLIENT)
                .set(CLIENT.COMPLETED_VISIT_COUNT, CLIENT.COMPLETED_VISIT_COUNT.plus(1))
                .set(CLIENT.COMPLETED_VALUE_MINOR, CLIENT.COMPLETED_VALUE_MINOR.plus(valueMinor))
                .set(CLIENT.LAST_VISIT_AT, Utc.toLocal(when))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
        dsl.update(CLIENT)
                .set(CLIENT.FIRST_VISIT_AT, Utc.toLocal(when))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)).and(CLIENT.FIRST_VISIT_AT.isNull()))
                .execute();
    }

    void bumpNoShow(UUID id) {
        dsl.update(CLIENT)
                .set(CLIENT.NO_SHOW_COUNT, CLIENT.NO_SHOW_COUNT.plus(1))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    void bumpLateCancel(UUID id) {
        dsl.update(CLIENT)
                .set(CLIENT.LATE_CANCEL_COUNT, CLIENT.LATE_CANCEL_COUNT.plus(1))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    void anonymize(UUID id) {
        dsl.update(CLIENT)
                .set(CLIENT.DISPLAY_NAME, "anonymized")
                .set(CLIENT.NORMALIZED_PHONE, "anon-" + id)
                .set(CLIENT.NOTES, (String) null)
                .set(CLIENT.TELEGRAM_CHAT_ID, (String) null)
                .set(CLIENT.ANONYMIZED_AT, Utc.toLocal(Instant.now()))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    private ClientView toView(Record r) {
        return new ClientView(
                r.get(CLIENT.ID),
                r.get(CLIENT.NORMALIZED_PHONE),
                r.get(CLIENT.DISPLAY_NAME),
                r.get(CLIENT.NOTES),
                r.get(CLIENT.TAGS),
                r.get(CLIENT.PREFERRED_LOCALE),
                r.get(CLIENT.TELEGRAM_CHAT_ID),
                Boolean.TRUE.equals(r.get(CLIENT.MARKETING_CONSENT)),
                r.get(CLIENT.FIRST_VISIT_AT) == null ? null : Utc.toInstant(r.get(CLIENT.FIRST_VISIT_AT)),
                r.get(CLIENT.LAST_VISIT_AT) == null ? null : Utc.toInstant(r.get(CLIENT.LAST_VISIT_AT)),
                r.get(CLIENT.COMPLETED_VISIT_COUNT),
                r.get(CLIENT.NO_SHOW_COUNT),
                r.get(CLIENT.LATE_CANCEL_COUNT),
                r.get(CLIENT.COMPLETED_VALUE_MINOR)
        );
    }
}
