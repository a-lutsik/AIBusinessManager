package com.cadence.platform.ai;

/** {@code ai_tenant_settings} of the current tenant (ADR 0002 §5, §9). */
public interface AiSettingsStore {

    /** {@code monthlyTokenBudget} null: the configured default applies. */
    record Settings(boolean notesToLlm, Long monthlyTokenBudget) {

        public static final Settings DEFAULTS = new Settings(false, null);
    }

    /** {@link Settings#DEFAULTS} when the tenant has no row. */
    Settings get();

    void setNotesToLlm(boolean notesToLlm);

    /** Operator setting (TD-42); null restores the default. Must not be negative. */
    void setMonthlyTokenBudget(Long monthlyTokenBudget);
}
