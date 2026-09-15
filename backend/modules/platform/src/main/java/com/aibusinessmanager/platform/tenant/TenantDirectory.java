package com.aibusinessmanager.platform.tenant;

import com.aibusinessmanager.platform.error.DomainException;
import com.aibusinessmanager.platform.jooq.tables.records.TenantRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.aibusinessmanager.platform.jooq.Tables.TENANT;

@Repository
public class TenantDirectory {

    private final DSLContext dsl;

    public TenantDirectory(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<TenantRecord> findBySlug(String slug) {
        return dsl.selectFrom(TENANT).where(TENANT.SLUG.eq(slug)).fetchOptional();
    }

    public TenantRecord requireBySlug(String slug) {
        return findBySlug(slug)
                .orElseThrow(() -> DomainException.notFound("TENANT_NOT_FOUND", "Unknown booking page"));
    }

    public Optional<TenantRecord> findById(UUID id) {
        return dsl.selectFrom(TENANT).where(TENANT.ID.eq(id)).fetchOptional();
    }

    public TenantRecord requireById(UUID id) {
        return findById(id)
                .orElseThrow(() -> DomainException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
    }

    public List<TenantRecord> findAll() {
        return dsl.selectFrom(TENANT).orderBy(TENANT.DISPLAY_NAME.asc()).fetch();
    }

    public void upsert(UUID id, String slug, String displayName, String timezone, String currency, String country) {
        dsl.insertInto(TENANT)
                .set(TENANT.ID, id)
                .set(TENANT.SLUG, slug)
                .set(TENANT.DISPLAY_NAME, displayName)
                .set(TENANT.TIMEZONE, timezone)
                .set(TENANT.CURRENCY_CODE, currency)
                .set(TENANT.COUNTRY_CODE, country)
                .onConflict(TENANT.ID)
                .doUpdate()
                .set(TENANT.DISPLAY_NAME, displayName)
                .set(TENANT.TIMEZONE, timezone)
                .set(TENANT.CURRENCY_CODE, currency)
                .set(TENANT.COUNTRY_CODE, country)
                .execute();
    }
}
