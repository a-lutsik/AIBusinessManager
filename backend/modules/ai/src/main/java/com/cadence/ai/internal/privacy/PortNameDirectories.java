package com.cadence.ai.internal.privacy;

import com.cadence.catalog.api.CatalogService;
import com.cadence.crm.api.CrmService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** {@link OwnerMessageMasker} lookups over the catalog and CRM public ports (each call is its own read transaction). */
final class PortNameDirectories {

    private PortNameDirectories() {
    }

    /**
     * Every master, inactive ones included ({@code listSpecialists(false)}): a former employee's name is personal data
     * too, and the owner may still ask about their past visits.
     */
    @Component
    static class CatalogMasters implements OwnerMessageMasker.Masters {
        private final CatalogService catalog;

        CatalogMasters(CatalogService catalog) {
            this.catalog = catalog;
        }

        @Override
        public List<OwnerMessageMasker.Person> all() {
            return catalog.listSpecialists(false).stream()
                    .map(s -> new OwnerMessageMasker.Person(s.id(), s.displayName()))
                    .toList();
        }
    }

    /** Whole-word name matches in one query; only id and name reach the masker, anonymized clients never. */
    @Component
    static class CrmClients implements OwnerMessageMasker.Clients {
        private final CrmService crm;

        CrmClients(CrmService crm) {
            this.crm = crm;
        }

        @Override
        public List<OwnerMessageMasker.Person> findByNameWords(Set<String> words) {
            return crm.findByNameWords(words).stream()
                    .map(c -> new OwnerMessageMasker.Person(c.id(), c.displayName()))
                    .toList();
        }
    }
}
