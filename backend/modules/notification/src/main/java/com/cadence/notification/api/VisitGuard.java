package com.cadence.notification.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Asked by the delivery worker right before a message goes out, in a short transaction: is the visit still what the message
 * says (not cancelled, still confirmed where the message needs it, still at {@code serviceStart})? The booking side answers
 * after re-reading the visit under a share lock. Tenant scope required.
 */
public interface VisitGuard {

    boolean stillDeliverable(UUID appointmentId, MessageKind kind, Instant serviceStart);
}
