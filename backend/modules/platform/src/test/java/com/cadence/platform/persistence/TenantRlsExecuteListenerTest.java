package com.cadence.platform.persistence;

import com.cadence.platform.tenancy.RlsNotes;
import com.cadence.platform.tenancy.TenantContext;
import org.jooq.ExecuteContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantRlsExecuteListenerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ExecuteContext ctx = mock(ExecuteContext.class);

    @AfterEach
    void reset() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TenantContext.clear();
    }

    private void wireConnection() throws Exception {
        when(ctx.connection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
    }

    @Test
    void setsCurrentTenantOnTheStatementConnectionInsideTransaction() throws Exception {
        wireConnection();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TenantContext.set(TENANT);

        new TenantRlsExecuteListener(false).executeStart(ctx);

        InOrder order = inOrder(connection, statement);
        order.verify(connection).prepareStatement("select set_config(?, ?, true)");
        order.verify(statement).setString(1, RlsNotes.SETTING_NAME);
        order.verify(statement).setString(2, TENANT.toString());
        order.verify(statement).execute();
        order.verify(statement).close();
    }

    @Test
    void setsEmptyTenantWhenContextIsEmptyInsideTransaction() throws Exception {
        wireConnection();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        new TenantRlsExecuteListener(false).executeStart(ctx);

        verify(statement).setString(1, RlsNotes.SETTING_NAME);
        verify(statement).setString(2, "");
        verify(statement).execute();
    }

    @Test
    void doesNothingOutsideTransaction() throws Exception {
        TenantContext.set(TENANT);

        assertThatCode(() -> new TenantRlsExecuteListener(false).executeStart(ctx)).doesNotThrowAnyException();

        verify(ctx, never()).connection();
        verifyNoInteractions(connection, statement);
    }

    @Test
    void strictModeRejectsStatementOutsideTransaction() {
        TenantContext.set(TENANT);

        assertThatThrownBy(() -> new TenantRlsExecuteListener(true).executeStart(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");

        verifyNoInteractions(connection, statement);
    }
}
