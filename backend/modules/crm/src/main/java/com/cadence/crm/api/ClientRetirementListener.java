package com.cadence.crm.api;

import java.util.UUID;

/**
 * Told that a client is about to be anonymized or merged away, in the same transaction and before the client row is written
 * or locked. Lets modules CRM must not depend on forget what they hold about the person; a failure aborts the retirement.
 */
public interface ClientRetirementListener {

    void clientRetiring(UUID clientId);
}
