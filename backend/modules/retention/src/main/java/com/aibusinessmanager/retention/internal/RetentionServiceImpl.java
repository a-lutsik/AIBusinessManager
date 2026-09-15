package com.aibusinessmanager.retention.internal;

import com.aibusinessmanager.catalog.api.BookingRulesView;
import com.aibusinessmanager.catalog.api.CatalogService;
import com.aibusinessmanager.crm.api.ClientView;
import com.aibusinessmanager.crm.api.CrmService;
import com.aibusinessmanager.platform.error.DomainException;
import com.aibusinessmanager.platform.persistence.TenantAwareDsl;
import com.aibusinessmanager.platform.tenancy.TenantContext;
import com.aibusinessmanager.platform.time.Utc;
import com.aibusinessmanager.retention.api.RetentionService;
import com.aibusinessmanager.retention.api.TrustView;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.aibusinessmanager.platform.jooq.Tables.SERVICE_PACKAGE;
import static com.aibusinessmanager.platform.jooq.Tables.PACKAGE_LEDGER;
import static com.aibusinessmanager.platform.jooq.Tables.TRUST_STATE;

@Service
public class RetentionServiceImpl implements RetentionService {

    private final CrmService crmService;
    private final CatalogService catalogService;
    private final DSLContext dsl;

    public RetentionServiceImpl(@Lazy CrmService crmService, @Lazy CatalogService catalogService, DSLContext dsl) {
        this.crmService = crmService;
        this.catalogService = catalogService;
        this.dsl = dsl;
    }

    @Override
    public String moduleName() {
        return "retention";
    }

    @Override
    @Transactional
    public TrustView assess(UUID clientId) {
        TrustView computed = compute(clientId);
        persist(computed);
        return computed;
    }

    @Override
    @Transactional
    public TrustView override(UUID clientId, String level, String reason) {
        TrustView computed = compute(clientId);
        persist(computed);
        dsl.update(TRUST_STATE)
                .set(TRUST_STATE.OVERRIDE_LEVEL, level)
                .set(TRUST_STATE.OVERRIDE_REASON, reason)
                .set(TRUST_STATE.UPDATED_AT, Utc.toLocal(Instant.now()))
                .where(TenantAwareDsl.tenantEquals(TRUST_STATE.TENANT_ID).and(TRUST_STATE.CLIENT_ID.eq(clientId)))
                .execute();
        return compute(clientId);
    }

    @Override
    @Transactional
    public boolean requiresConfirmation(UUID clientId) {
        TrustView trust = compute(clientId);
        BookingRulesView rules = catalogService.rules();
        if (trust.isLow()) {
            return true;
        }
        return trust.isNew() && rules.newClientRequiresConfirmation();
    }

    private TrustView compute(UUID clientId) {
        ClientView client = crmService.findById(clientId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client not found"));
        BookingRulesView rules = catalogService.rules();
        List<String> reasons = new ArrayList<>();
        String level;
        if (client.completedVisitCount() == 0) {
            level = "NEW";
            reasons.add("No completed visits yet");
        } else {
            int recentNoShows = client.noShowCount();
            int lateCancels = client.lateCancelCount();
            if (recentNoShows >= 2 || (recentNoShows >= 1 && client.completedVisitCount() <= 5)) {
                level = "LOW";
                reasons.add(recentNoShows + " no-shows recorded");
            } else if (lateCancels >= 2) {
                level = "LOW";
                reasons.add(lateCancels + " late cancellations (threshold " + rules.lateCancellationHours() + "h)");
            } else if (recentNoShows == 0 && lateCancels == 0 && client.completedVisitCount() >= 3) {
                level = "HIGH";
                reasons.add(client.completedVisitCount() + " completed visits, 0 no-shows");
            } else {
                level = "NORMAL";
                reasons.add(client.completedVisitCount() + " completed visits");
            }
        }
        var stored = dsl.selectFrom(TRUST_STATE)
                .where(TenantAwareDsl.tenantEquals(TRUST_STATE.TENANT_ID).and(TRUST_STATE.CLIENT_ID.eq(clientId)))
                .fetchOptional();
        String overrideLevel = stored.map(r -> r.get(TRUST_STATE.OVERRIDE_LEVEL)).orElse(null);
        String overrideReason = stored.map(r -> r.get(TRUST_STATE.OVERRIDE_REASON)).orElse(null);
        return new TrustView(clientId, level, reasons, overrideLevel, overrideReason);
    }

    private void persist(TrustView trust) {
        dsl.insertInto(TRUST_STATE)
                .set(TRUST_STATE.TENANT_ID, TenantContext.require())
                .set(TRUST_STATE.CLIENT_ID, trust.clientId())
                .set(TRUST_STATE.LEVEL, trust.level())
                .set(TRUST_STATE.REASONS, String.join("; ", trust.reasons()))
                .set(TRUST_STATE.OVERRIDE_LEVEL, trust.overrideLevel())
                .set(TRUST_STATE.OVERRIDE_REASON, trust.overrideReason())
                .set(TRUST_STATE.UPDATED_AT, Utc.toLocal(Instant.now()))
                .onConflict(TRUST_STATE.TENANT_ID, TRUST_STATE.CLIENT_ID)
                .doUpdate()
                .set(TRUST_STATE.LEVEL, trust.level())
                .set(TRUST_STATE.REASONS, String.join("; ", trust.reasons()))
                .execute();
    }

    @Override
    @Transactional
    public PackageView sellPackage(UUID clientId, String name, int sessions, int valueMinor, String currencyCode) {
        UUID id = UUID.randomUUID();
        dsl.insertInto(SERVICE_PACKAGE)
                .set(SERVICE_PACKAGE.ID, id)
                .set(SERVICE_PACKAGE.TENANT_ID, TenantContext.require())
                .set(SERVICE_PACKAGE.CLIENT_ID, clientId)
                .set(SERVICE_PACKAGE.NAME, name)
                .set(SERVICE_PACKAGE.TOTAL_SESSIONS, sessions)
                .set(SERVICE_PACKAGE.REMAINING_SESSIONS, sessions)
                .set(SERVICE_PACKAGE.VALUE_MINOR, valueMinor)
                .set(SERVICE_PACKAGE.CURRENCY_CODE, currencyCode)
                .set(SERVICE_PACKAGE.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
        dsl.insertInto(PACKAGE_LEDGER)
                .set(PACKAGE_LEDGER.ID, UUID.randomUUID())
                .set(PACKAGE_LEDGER.TENANT_ID, TenantContext.require())
                .set(PACKAGE_LEDGER.PACKAGE_ID, id)
                .set(PACKAGE_LEDGER.DELTA_SESSIONS, sessions)
                .set(PACKAGE_LEDGER.NOTE, "sold")
                .set(PACKAGE_LEDGER.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
        return new PackageView(id, clientId, name, sessions, sessions, valueMinor);
    }

    @Override
    @Transactional
    public void deductSession(UUID packageId, UUID appointmentId) {
        var pkg = dsl.selectFrom(SERVICE_PACKAGE)
                .where(TenantAwareDsl.tenantEquals(SERVICE_PACKAGE.TENANT_ID).and(SERVICE_PACKAGE.ID.eq(packageId)))
                .fetchOptional()
                .orElseThrow(() -> DomainException.notFound("PACKAGE_NOT_FOUND", "Package not found"));
        if (pkg.get(SERVICE_PACKAGE.REMAINING_SESSIONS) <= 0) {
            throw DomainException.conflict("PACKAGE_EMPTY", "No sessions remaining");
        }
        dsl.update(SERVICE_PACKAGE)
                .set(SERVICE_PACKAGE.REMAINING_SESSIONS, SERVICE_PACKAGE.REMAINING_SESSIONS.minus(1))
                .where(SERVICE_PACKAGE.ID.eq(packageId))
                .execute();
        dsl.insertInto(PACKAGE_LEDGER)
                .set(PACKAGE_LEDGER.ID, UUID.randomUUID())
                .set(PACKAGE_LEDGER.TENANT_ID, TenantContext.require())
                .set(PACKAGE_LEDGER.PACKAGE_ID, packageId)
                .set(PACKAGE_LEDGER.APPOINTMENT_ID, appointmentId)
                .set(PACKAGE_LEDGER.DELTA_SESSIONS, -1)
                .set(PACKAGE_LEDGER.NOTE, "visit")
                .set(PACKAGE_LEDGER.CREATED_AT, Utc.toLocal(Instant.now()))
                .execute();
    }
}
