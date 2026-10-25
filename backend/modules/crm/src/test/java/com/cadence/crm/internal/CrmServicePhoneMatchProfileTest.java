package com.cadence.crm.internal;

import com.cadence.crm.api.ClientView;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Decision D (stage 03): a booking that matches an existing client by phone (public, master or owner path, all through
 * {@code findOrCreate}) may only add marketing consent: {@code true} sets it with the booking's consent version (and
 * timestamp), {@code false} never revokes it and leaves the stored consent untouched. The locale is set only when the
 * client has none, and the stored name is never replaced by the typed one.
 */
class CrmServicePhoneMatchProfileTest {

    private static final String PHONE = "+995555000222";

    private final ClientRepository repository = mock(ClientRepository.class);
    private final CrmServiceImpl service = new CrmServiceImpl(repository, mock(PlatformTransactionManager.class), mock(com.cadence.platform.audit.AuditLogger.class));

    private ClientView existing(String locale, boolean consent) {
        UUID id = UUID.randomUUID();
        ClientView client = new ClientView(id, PHONE, "Stored Name", null, null, locale, null, consent,
                null, null, 0, 0, 0, 0);
        when(repository.findByPhone(PHONE)).thenReturn(Optional.of(client));
        when(repository.findById(id)).thenReturn(Optional.of(client));
        return client;
    }

    @Test
    void consentFalseNeverRevokesAnExistingConsent() {
        ClientView client = existing("ru", true);

        service.findOrCreate(PHONE, "Typed", "en", false, "public-v1");

        verify(repository).updateProfile(eq(client.id()), eq("Stored Name"), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void consentFalseLeavesAMissingConsentAlone() {
        ClientView client = existing("ru", false);

        service.findOrCreate(PHONE, "Typed", "en", false, "public-v1");

        verify(repository).updateProfile(eq(client.id()), eq("Stored Name"), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void consentTrueIsAddedWithTheNewVersion() {
        ClientView client = existing("ru", false);

        service.findOrCreate(PHONE, "Typed", "en", true, "public-v2");

        verify(repository).updateProfile(eq(client.id()), eq("Stored Name"), isNull(), isNull(), isNull(), eq(true), eq("public-v2"));
    }

    @Test
    void localeIsSetOnlyWhenTheClientHasNone() {
        ClientView withoutLocale = existing(null, false);

        service.findOrCreate(PHONE, "Typed", "ka", false, null);

        verify(repository).updateProfile(eq(withoutLocale.id()), eq("Stored Name"), isNull(), isNull(), eq("ka"), isNull(), isNull());
    }

    @Test
    void theStoredNameIsKeptAndNoClientIsInserted() {
        ClientView client = existing("en", true);

        ClientView result = service.findOrCreate(PHONE, "Somebody Else", "ru", false, null);

        assertThat(result.displayName()).isEqualTo("Stored Name");
        verify(repository, never()).insert(any());
        verify(repository).updateProfile(eq(client.id()), eq("Stored Name"), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void aNewClientStillStartsWithTheBookingsConsentAndLocale() {
        when(repository.findByPhone(PHONE)).thenReturn(Optional.empty());
        when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findById(any())).thenAnswer(inv -> Optional.of(new ClientView(inv.getArgument(0), PHONE,
                "Typed", null, null, "ka", null, false, null, null, 0, 0, 0, 0)));

        service.findOrCreate(PHONE, "Typed", "ka", false, "public-v1");

        verify(repository).updateProfile(any(), eq("Typed"), isNull(), isNull(), eq("ka"), eq(false), eq("public-v1"));
    }
}
