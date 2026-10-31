package com.cadence.crm.internal;

import com.cadence.crm.api.ClientRetirementListener;
import com.cadence.crm.api.ClientView;
import com.cadence.platform.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Modules are told before the client row is written or locked: their locks come first, see {@code DraftApplier}. */
class CrmServiceRetirementTest {

    private final ClientRepository repository = mock(ClientRepository.class);
    private final ClientRetirementListener listener = mock(ClientRetirementListener.class);
    private final CrmServiceImpl service = new CrmServiceImpl(
            repository, mock(PlatformTransactionManager.class), mock(AuditLogger.class), List.of(listener));
    private final UUID absorbed = UUID.randomUUID();
    private final UUID survivor = UUID.randomUUID();

    @Test
    void anonymizeTellsListenersFirst() {
        when(repository.findById(absorbed)).thenReturn(Optional.of(mock(ClientView.class)));

        service.anonymize(absorbed);

        InOrder order = inOrder(listener, repository);
        order.verify(listener).clientRetiring(absorbed);
        order.verify(repository).anonymize(absorbed);
    }

    @Test
    void mergeTellsListenersAboutTheAbsorbedClientBeforeLocking() {
        ClientRepository.ClientState active = new ClientRepository.ClientState(mock(ClientView.class), null, null);
        when(repository.findState(any())).thenReturn(Optional.of(active));

        service.lockForMerge(survivor, absorbed);

        InOrder order = inOrder(listener, repository);
        order.verify(listener).clientRetiring(absorbed);
        order.verify(repository).lockForUpdate(survivor, absorbed);
    }
}
