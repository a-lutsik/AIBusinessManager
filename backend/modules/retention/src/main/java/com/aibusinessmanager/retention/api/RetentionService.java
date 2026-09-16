package com.aibusinessmanager.retention.api;

import java.util.List;
import java.util.UUID;

/** Public retention / trust port. */
public interface RetentionService {

    String moduleName();

    TrustView assess(UUID clientId);

    TrustView compute(UUID clientId);

    TrustView override(UUID clientId, String level, String reason);

    boolean requiresConfirmation(UUID clientId);

    record PackageView(UUID id, UUID clientId, String name, int remainingSessions, int totalSessions, int valueMinor) {
    }

    PackageView sellPackage(UUID clientId, String name, int sessions, int valueMinor, String currencyCode);

    List<PackageView> packagesForClient(UUID clientId);

    void deductSession(UUID packageId, UUID appointmentId);
}
