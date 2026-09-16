package com.cadence.crm.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.platform.error.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CrmServiceImpl implements CrmService {

    private final ClientRepository repository;

    public CrmServiceImpl(ClientRepository repository) {
        this.repository = repository;
    }

    @Override
    public String moduleName() {
        return "crm";
    }

    @Override
    @Transactional
    public ClientView findOrCreate(
            String e164Phone,
            String displayName,
            String locale,
            boolean marketingConsent,
            String consentVersion
    ) {
        Optional<ClientView> existing = repository.findByPhone(e164Phone);
        if (existing.isPresent()) {
            ClientView row = existing.get();
            repository.updateProfile(row.id(), row.displayName(), null, null, locale, marketingConsent, consentVersion);
            return repository.findById(row.id()).orElse(row);
        }
        ClientView created = new ClientView(
                UUID.randomUUID(),
                e164Phone,
                displayName,
                null,
                null,
                locale,
                null,
                marketingConsent,
                null,
                null,
                0,
                0,
                0,
                0
        );
        repository.insert(created);
        repository.updateProfile(created.id(), displayName, null, null, locale, marketingConsent, consentVersion);
        return repository.findById(created.id()).orElse(created);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClientView> findById(UUID clientId) {
        return repository.findById(clientId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClientView> findByPhone(String e164Phone) {
        return repository.findByPhone(e164Phone);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClientView> search(String query) {
        return repository.search(query);
    }

    @Override
    @Transactional
    public ClientView updateNotes(UUID clientId, String notes, String tags) {
        ClientView existing = repository.findById(clientId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client not found"));
        repository.updateProfile(clientId, existing.displayName(), notes, tags, null, null, null);
        return repository.findById(clientId).orElse(existing);
    }

    @Override
    @Transactional
    public void recordCompletedVisit(UUID clientId, Instant when, int valueMinor) {
        repository.bumpCompleted(clientId, when, valueMinor);
    }

    @Override
    @Transactional
    public void recordNoShow(UUID clientId) {
        repository.bumpNoShow(clientId);
    }

    @Override
    @Transactional
    public void recordLateCancel(UUID clientId) {
        repository.bumpLateCancel(clientId);
    }

    @Override
    @Transactional
    public ClientView anonymize(UUID clientId) {
        repository.anonymize(clientId);
        return repository.findById(clientId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client not found"));
    }

    @Override
    @Transactional
    public MergeResult merge(UUID survivingId, UUID absorbedId) {
        if (survivingId.equals(absorbedId)) {
            throw DomainException.badRequest("INVALID_MERGE", "Cannot merge a client with itself");
        }
        repository.findById(survivingId).orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Survivor not found"));
        repository.findById(absorbedId).orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Absorbed client not found"));
        repository.anonymize(absorbedId);
        return new MergeResult(survivingId, absorbedId);
    }
}
