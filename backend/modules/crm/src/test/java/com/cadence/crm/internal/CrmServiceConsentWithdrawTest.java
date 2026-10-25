package com.cadence.crm.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PRODUCT_PLAN §10: marketing consent can be withdrawn by the booking link and, on request, by the owner. Decision D
 * makes bookings unable to revoke it, so this is the only way back to {@code false}; it is audited with its source.
 */
class CrmServiceConsentWithdrawTest {

    private final ClientRepository repository = mock(ClientRepository.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final CrmServiceImpl service = new CrmServiceImpl(repository, mock(PlatformTransactionManager.class), audit);

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    @Test
    void withdrawalSetsConsentFalseWithItsSourceAndIsAudited() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new ClientView(id, "+995555000333", "Mariam", null, null,
                "ka", null, true, null, null, 0, 0, 0, 0)));
        ActorContext.set(new ActorContext.Actor("owner-1", ActorContext.Role.OWNER, null));

        service.withdrawMarketingConsent(id, "owner");

        verify(repository).updateProfile(eq(id), eq("Mariam"), isNull(), isNull(), isNull(), eq(false), eq("withdrawn:owner"));
        verify(audit).record(eq("owner-1"), eq("OWNER"), eq("client.consent.withdraw"), eq("client"), eq(id),
                eq("{\"marketingConsent\":true}"), eq("{\"marketingConsent\":false}"), eq("owner"), isNull());
    }

    @Test
    void anUnknownClientIsNotFoundAndNothingIsWritten() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdrawMarketingConsent(id, "public-link"))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.status()).isEqualTo(404));
        verify(repository, never()).updateProfile(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(audit);
    }
}
