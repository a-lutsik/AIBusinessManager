package com.cadence.notification.internal;

import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.VisitGuard;
import com.cadence.platform.i18n.MessageCatalog;
import com.cadence.platform.tenant.TenantDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MessageDeliveryWorkerTest {

    @Test
    void aStoppingContextClaimsNoNewRows() {
        DispatchQueue queue = mock(DispatchQueue.class);
        MessageDeliveryWorker worker = new MessageDeliveryWorker(queue, mock(VisitGuard.class), mock(CrmService.class),
                mock(TenantDirectory.class), mock(MessageCatalog.class), mock(TelegramNotificationChannel.class),
                mock(ManualTaskNotificationChannel.class), mock(DeliveryJournal.class), mock(PlatformTransactionManager.class), true);

        worker.onContextClosing();

        assertThat(worker.runOnce()).isZero();
        verifyNoInteractions(queue);
    }
}
