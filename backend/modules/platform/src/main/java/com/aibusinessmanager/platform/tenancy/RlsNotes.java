package com.aibusinessmanager.platform.tenancy;

/**
 * Documents the RLS / connection-pool contract. Concrete interceptor lands with booking writes.
 *
 * <pre>
 * On transaction begin:  SET LOCAL app.current_tenant_id = '&lt;uuid&gt;'
 * On connection return:  RESET app.current_tenant_id  (or discard / discard all)
 * Soft barrier:          TenantAware DSL adds tenant_id to every jOOQ query
 * Hard barrier:          Postgres RLS policies on tenant-owned tables
 * </pre>
 */
public final class RlsNotes {

    public static final String SETTING_NAME = "app.current_tenant_id";

    private RlsNotes() {
    }
}
