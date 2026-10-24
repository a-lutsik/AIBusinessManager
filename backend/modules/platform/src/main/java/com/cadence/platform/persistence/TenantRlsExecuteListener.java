package com.cadence.platform.persistence;

import com.cadence.platform.tenancy.RlsNotes;
import com.cadence.platform.tenancy.TenantContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds the Postgres RLS setting {@value RlsNotes#SETTING_NAME} to {@link TenantContext} before every jOOQ
 * statement, on that statement's own connection.
 *
 * <p>Why per statement and not once per transaction:
 * <ul>
 *   <li>{@link TenantContext} may change inside one transaction ({@code TenantContext.open(...)} in token
 *       lookups, the outbox poller, the seeder).</li>
 *   <li>{@code set_config(..., true)} is transaction-local and is undone by {@code ROLLBACK TO SAVEPOINT}, so a
 *       value cached after a nested (savepoint) transaction rolled back would be wrong.</li>
 * </ul>
 * Cost: one extra round trip ({@code select set_config(...)}) per statement. Accepted for correctness; do not add
 * caching without handling savepoint rollback.
 *
 * <p>Outside a Spring transaction nothing is set: an auto-commit {@code set_config(..., true)} would end with its
 * own implicit transaction, and RLS then fails closed (no rows visible, inserts rejected). Such statements are a
 * bug: they are logged once per call site, or rejected with {@link IllegalStateException} when
 * {@code app.persistence.require-transaction=true}.
 *
 * <p>The setting is applied through raw JDBC on {@link ExecuteContext#connection()}, so it does not re-enter jOOQ
 * execute listeners.
 */
public class TenantRlsExecuteListener implements ExecuteListener {

    static final String SET_TENANT_SQL = "select set_config(?, ?, true)";

    private static final Logger log = LoggerFactory.getLogger(TenantRlsExecuteListener.class);
    private static final String OWN_PACKAGE = TenantRlsExecuteListener.class.getPackageName();

    private final boolean requireTransaction;
    private final Set<String> warnedCallSites = ConcurrentHashMap.newKeySet();

    public TenantRlsExecuteListener(boolean requireTransaction) {
        this.requireTransaction = requireTransaction;
    }

    @Override
    public void executeStart(ExecuteContext ctx) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            onStatementOutsideTransaction(ctx);
            return;
        }
        String tenant = TenantContext.current().map(UUID::toString).orElse("");
        Connection connection = ctx.connection();
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT_SQL)) {
            statement.setString(1, RlsNotes.SETTING_NAME);
            statement.setString(2, tenant);
            statement.execute();
        } catch (SQLException ex) {
            throw new DataAccessException("Could not bind " + RlsNotes.SETTING_NAME + " for RLS", ex);
        }
    }

    private void onStatementOutsideTransaction(ExecuteContext ctx) {
        String callSite = callSite();
        if (requireTransaction) {
            throw new IllegalStateException("jOOQ statement executed outside a transaction at " + callSite
                    + "; tenant RLS context cannot be bound. Annotate the service method with @Transactional.");
        }
        if (warnedCallSites.add(callSite)) {
            log.warn("jOOQ statement executed outside a transaction at {}: RLS tenant context is not bound, "
                    + "tenant-owned tables will return no rows. SQL: {}", callSite, ctx.sql());
        }
    }

    /** First application frame outside this package, e.g. {@code com.cadence.x.Repo#method:42}. */
    private static String callSite() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> f.getClassName().startsWith("com.cadence.")
                        && !f.getClassName().startsWith(OWN_PACKAGE + "."))
                .findFirst()
                .map(f -> f.getClassName() + "#" + f.getMethodName() + ":" + f.getLineNumber())
                .orElse("unknown"));
    }
}
