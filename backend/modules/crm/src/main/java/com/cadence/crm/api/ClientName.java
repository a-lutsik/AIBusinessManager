package com.cadence.crm.api;

import java.util.UUID;

/** A client as the AI name masking needs it: no phone, notes or other profile fields. */
public record ClientName(UUID id, String displayName) {
}
