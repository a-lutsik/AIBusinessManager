package com.aibusinessmanager.platform.audit;

import java.util.UUID;

/**
 * Platform audit port. Audit log becomes a Timescale hypertable later; outbox stays regular.
 */
public interface AuditLogger {

    void record(
            String actorId,
            String actorType,
            String action,
            String entityType,
            UUID entityId,
            String beforeJson,
            String afterJson,
            String source,
            String requestId
    );
}
