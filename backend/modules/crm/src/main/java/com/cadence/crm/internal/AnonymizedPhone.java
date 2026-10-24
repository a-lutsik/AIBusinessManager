package com.cadence.crm.internal;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

/**
 * Placeholder stored in {@code client.normalized_phone} (VARCHAR(32), unique per tenant) once a profile is
 * anonymized or absorbed by a merge, so the real number is gone but the NOT NULL / unique constraints still hold.
 *
 * <p>Format: {@code "anon-"} + the 16 UUID bytes as unpadded base64url (22 characters) = 27 characters. The encoding is
 * injective, so distinct client ids never collide, and the value never starts with {@code '+'}, so it can never equal a
 * real E.164 number (which the application always stores with a leading plus).
 */
final class AnonymizedPhone {

    static final String PREFIX = "anon-";
    /** Column width of {@code client.normalized_phone} (V3). */
    static final int MAX_LENGTH = 32;

    private AnonymizedPhone() {
    }

    static String forClient(UUID clientId) {
        ByteBuffer bytes = ByteBuffer.allocate(16)
                .putLong(clientId.getMostSignificantBits())
                .putLong(clientId.getLeastSignificantBits());
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }
}
