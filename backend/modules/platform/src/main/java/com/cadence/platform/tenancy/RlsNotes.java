package com.cadence.platform.tenancy;

/**
 * Documents the tenant isolation contract between the application and Postgres row-level security.
 *
 * <pre>
 * Soft barrier:   TenantAwareDsl adds tenant_id to every jOOQ query.
 * Hard barrier:   RLS policy tenant_isolation on every tenant-owned table (V4):
 *                 tenant_id::text = NULLIF(current_setting('app.current_tenant_id', true), '')
 * Runtime role:   cadence_app (V7): not the table owner, NOBYPASSRLS, DML grants only.
 * Owner role:     cadence: runs Flyway, owns the tables, bypasses RLS (no FORCE ROW LEVEL SECURITY, on purpose:
 *                 owner-run migrations and the SECURITY DEFINER functions must see every tenant).
 * Binding:        TenantRlsExecuteListener runs select set_config('app.current_tenant_id', &lt;TenantContext or ''&gt;, true)
 *                 before every jOOQ statement inside a Spring transaction, on that statement's connection.
 *                 Transaction-local, so HikariCP cannot leak it to the next borrower; re-set per statement because
 *                 TenantContext can change mid-transaction and ROLLBACK TO SAVEPOINT reverts it.
 * No transaction: nothing is bound, RLS fails closed (no rows, inserts rejected). Logged once per call site, or an
 *                 IllegalStateException with app.persistence.require-transaction=true.
 * Cross-tenant:   only via SECURITY DEFINER functions executable by cadence_app:
 *                 booking_token_lookup(hash) for public booking links, outbox_claim(limit, max) for the poller.
 * </pre>
 */
public final class RlsNotes {

    public static final String SETTING_NAME = "app.current_tenant_id";

    private RlsNotes() {
    }
}
