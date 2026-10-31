package com.cadence.platform.ai;

import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static com.cadence.platform.jooq.Tables.AI_TENANT_SETTINGS;

@Repository
@Transactional
public class JooqAiSettingsStore implements AiSettingsStore {

    private final DSLContext dsl;
    private final Clock clock;

    public JooqAiSettingsStore(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    private LocalDateTime now() {
        return Utc.toLocal(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    @Override
    @Transactional(readOnly = true)
    public Settings get() {
        return dsl.selectFrom(AI_TENANT_SETTINGS)
                .where(TenantAwareDsl.tenantEquals(AI_TENANT_SETTINGS.TENANT_ID))
                .fetchOptional()
                .map(r -> new Settings(r.getNotesToLlm(), r.getMonthlyTokenBudget()))
                .orElse(Settings.DEFAULTS);
    }

    @Override
    public void setNotesToLlm(boolean notesToLlm) {
        LocalDateTime now = now();
        dsl.insertInto(AI_TENANT_SETTINGS)
                .set(AI_TENANT_SETTINGS.TENANT_ID, TenantContext.require())
                .set(AI_TENANT_SETTINGS.NOTES_TO_LLM, notesToLlm)
                .set(AI_TENANT_SETTINGS.UPDATED_AT, now)
                .onConflict(AI_TENANT_SETTINGS.TENANT_ID)
                .doUpdate()
                .set(AI_TENANT_SETTINGS.NOTES_TO_LLM, DSL.excluded(AI_TENANT_SETTINGS.NOTES_TO_LLM))
                .set(AI_TENANT_SETTINGS.UPDATED_AT, DSL.excluded(AI_TENANT_SETTINGS.UPDATED_AT))
                .execute();
    }

    @Override
    public void setMonthlyTokenBudget(Long monthlyTokenBudget) {
        if (monthlyTokenBudget != null && monthlyTokenBudget < 0) {
            throw new IllegalArgumentException("monthly token budget must not be negative");
        }
        LocalDateTime now = now();
        dsl.insertInto(AI_TENANT_SETTINGS)
                .set(AI_TENANT_SETTINGS.TENANT_ID, TenantContext.require())
                .set(AI_TENANT_SETTINGS.MONTHLY_TOKEN_BUDGET, monthlyTokenBudget)
                .set(AI_TENANT_SETTINGS.UPDATED_AT, now)
                .onConflict(AI_TENANT_SETTINGS.TENANT_ID)
                .doUpdate()
                .set(AI_TENANT_SETTINGS.MONTHLY_TOKEN_BUDGET, DSL.excluded(AI_TENANT_SETTINGS.MONTHLY_TOKEN_BUDGET))
                .set(AI_TENANT_SETTINGS.UPDATED_AT, DSL.excluded(AI_TENANT_SETTINGS.UPDATED_AT))
                .execute();
    }
}
