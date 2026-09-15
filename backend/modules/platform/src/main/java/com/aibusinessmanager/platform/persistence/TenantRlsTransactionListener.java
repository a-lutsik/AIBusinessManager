package com.aibusinessmanager.platform.persistence;

import com.aibusinessmanager.platform.tenancy.RlsNotes;
import com.aibusinessmanager.platform.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

import java.util.UUID;

/**
 * SET LOCAL app.current_tenant_id at the start of each Spring transaction.
 * SET LOCAL is transaction-scoped, so HikariCP cannot leak it to the next borrower.
 * DSLContext is resolved lazily to avoid a cycle with jOOQ's transaction provider.
 */
@Component
public class TenantRlsTransactionListener implements TransactionExecutionListener {

    private final ObjectProvider<DSLContext> dsl;

    public TenantRlsTransactionListener(ObjectProvider<DSLContext> dsl) {
        this.dsl = dsl;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (beginFailure != null) {
            return;
        }
        UUID tenantId = TenantContext.current().orElse(null);
        String value = tenantId == null ? "" : tenantId.toString();
        dsl.getObject().execute(
                "select set_config({0}, {1}, true)",
                DSL.inline(RlsNotes.SETTING_NAME),
                DSL.inline(value)
        );
    }
}
