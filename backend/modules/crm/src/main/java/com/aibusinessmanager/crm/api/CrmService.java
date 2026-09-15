package com.aibusinessmanager.crm.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public CRM port — clients, history aggregates, packages. */
public interface CrmService {

    String moduleName();

    ClientView findOrCreate(String e164Phone, String displayName, String locale, boolean marketingConsent, String consentVersion);

    Optional<ClientView> findById(UUID clientId);

    Optional<ClientView> findByPhone(String e164Phone);

    List<ClientView> search(String query);

    ClientView updateNotes(UUID clientId, String notes, String tags);

    void recordCompletedVisit(UUID clientId, Instant when, int valueMinor);

    void recordNoShow(UUID clientId);

    void recordLateCancel(UUID clientId);

    ClientView anonymize(UUID clientId);

    record MergeResult(UUID survivingId, UUID absorbedId) {
    }

    MergeResult merge(UUID survivingId, UUID absorbedId);
}
