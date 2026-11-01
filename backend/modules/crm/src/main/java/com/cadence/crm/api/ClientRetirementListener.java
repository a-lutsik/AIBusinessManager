package com.cadence.crm.api;

import java.util.UUID;

/**
 * Told that a client is about to be anonymized or merged away, in the same transaction and before the client row is written
 * or locked. Lets modules CRM must not depend on forget what they hold about the person; a failure aborts the retirement.
 */
public interface ClientRetirementListener {

    void clientRetiring(UUID clientId);

    /**
     * The stronger case: the person asked to be erased, so a module also drops what it cannot tell apart from them. A merge never
     * comes here, the person lives on in the surviving profile. Same transaction and order as {@link #clientRetiring}.
     */
    default void clientErasing(UUID clientId) {
        clientRetiring(clientId);
    }
}
