package com.aibusinessmanager.platform.persistence;

import com.aibusinessmanager.platform.tenancy.TenantContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.UUID;

/**
 * Template helper: repositories must route tenant predicates through this DSL,
 * not ad-hoc "when remembered" filters.
 */
public final class TenantAwareDsl {

    private TenantAwareDsl() {
    }

    public static Condition tenantEquals(Field<UUID> tenantIdField) {
        UUID tenantId = TenantContext.require();
        return tenantIdField.eq(tenantId);
    }

    public static Condition tenantEquals(Field<UUID> tenantIdField, UUID tenantId) {
        return tenantIdField.eq(tenantId);
    }

    public static Condition alwaysFalse() {
        return DSL.falseCondition();
    }
}
