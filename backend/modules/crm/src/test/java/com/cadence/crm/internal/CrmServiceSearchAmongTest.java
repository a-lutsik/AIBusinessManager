package com.cadence.crm.internal;

import com.cadence.crm.api.ClientView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TD-18: the scoped search behind a MASTER's client list matches names only, and it cannot be turned into a phone
 * oracle: a query with three or more digits finds nothing (it never reaches the database), and clients whose display
 * name shows a phone (a client created without a name is stored with the phone as its name) are left out, by the same
 * rule the response policy uses. The owner's search keeps matching phones.
 */
class CrmServiceSearchAmongTest {

    private final ClientRepository repository = mock(ClientRepository.class);
    private final CrmServiceImpl service = new CrmServiceImpl(repository, mock(PlatformTransactionManager.class), mock(com.cadence.platform.audit.AuditLogger.class));

    private static ClientView client(String phone, String name) {
        return new ClientView(UUID.randomUUID(), phone, name, null, null, null, null, false, null, null, 0, 0, 0, 0);
    }

    @Test
    void scopedSearchMatchesNamesOnlyAcrossTheWholeScope() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

        service.searchAmong("Mari", ids);

        // The limit is the scope size, so leaving phone-named clients out afterwards never hides a named one.
        verify(repository).search("Mari", ids, false, ids.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"555", "99555", "+995 555", "5-5-5", "Anna 123", "a1b2c3"})
    void aQueryWithThreeOrMoreDigitsFindsNothing(String query) {
        assertThat(service.searchAmong(query, List.of(UUID.randomUUID()))).isEmpty();

        verify(repository, never()).search(anyString(), any(), anyBoolean(), anyInt());
    }

    @Test
    void aQueryWithFewerDigitsStillSearches() {
        List<UUID> ids = List.of(UUID.randomUUID());

        service.searchAmong("Anna 12", ids);

        verify(repository).search("Anna 12", ids, false, 1);
    }

    @Test
    void clientsWhoseNameShowsAPhoneAreLeftOut() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ClientView named = client("+995555000001", "Mariam");
        ClientView phoneNamed = client("+995555000002", "+995555000002");
        ClientView localDigits = client("+995555123456", "Anna 555 12 34 56");
        ClientView otherPhone = client("+995555000004", "Nino 599 11 22 33");
        when(repository.search(eq(null), eq(ids), eq(false), anyInt()))
                .thenReturn(List.of(named, phoneNamed, localDigits, otherPhone));

        assertThat(service.searchAmong(null, ids)).containsExactly(named);
    }

    @Test
    void theResultIsCappedAfterFiltering() {
        List<UUID> ids = new ArrayList<>();
        List<ClientView> found = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            ids.add(UUID.randomUUID());
            found.add(client("+99555500" + String.format("%04d", i), i % 2 == 0 ? "+99555500" + String.format("%04d", i) : "Client " + i));
        }
        when(repository.search(eq(""), eq(ids), eq(false), anyInt())).thenReturn(found);

        List<ClientView> result = service.searchAmong("", ids);

        assertThat(result).hasSize(75).allSatisfy(c -> assertThat(c.displayName()).startsWith("Client "));
    }

    @Test
    void emptyScopeNeverReachesTheRepository() {
        assertThat(service.searchAmong("x", List.of())).isEmpty();

        verify(repository, never()).search(anyString(), any(), anyBoolean(), anyInt());
    }

    @Test
    void ownerSearchStillMatchesPhones() {
        service.search("99555");

        verify(repository).search("99555");
    }
}
