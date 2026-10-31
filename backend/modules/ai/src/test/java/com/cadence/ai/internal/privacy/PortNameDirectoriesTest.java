package com.cadence.ai.internal.privacy;

import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.crm.api.ClientName;
import com.cadence.crm.api.CrmService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortNameDirectoriesTest {

    @Test
    void mastersIncludeInactiveOnes() {
        CatalogService catalog = mock(CatalogService.class);
        UUID id = UUID.randomUUID();
        when(catalog.listSpecialists(false)).thenReturn(List.of(new SpecialistView(id, "Nino", null, null, false)));

        List<OwnerMessageMasker.Person> masters = new PortNameDirectories.CatalogMasters(catalog).all();

        assertEquals(List.of(new OwnerMessageMasker.Person(id, "Nino")), masters);
        verify(catalog).listSpecialists(false);
    }

    @Test
    void clientsComeFromTheWholeWordQueryAsIdAndNameOnly() {
        CrmService crm = mock(CrmService.class);
        UUID id = UUID.randomUUID();
        when(crm.findByNameWords(Set.of("anna"))).thenReturn(List.of(new ClientName(id, "Anna Ivanova")));

        assertEquals(List.of(new OwnerMessageMasker.Person(id, "Anna Ivanova")),
                new PortNameDirectories.CrmClients(crm).findByNameWords(Set.of("anna")));
    }
}
