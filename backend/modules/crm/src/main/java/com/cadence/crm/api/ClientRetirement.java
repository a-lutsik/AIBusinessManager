package com.cadence.crm.api;

import java.time.Instant;
import java.util.UUID;

/** Whether a profile still stands for a person: a merged-away profile is anonymized as well and names the survivor. */
public record ClientRetirement(Instant anonymizedAt, UUID mergedIntoClientId) {
}
