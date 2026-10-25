package com.cadence.retention.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.retention.api.TrustView;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision B (stage 03): every trust assessment carries number-free reason codes next to the owner's texts, so a MASTER
 * can see why a client is LOW/HIGH without the salon-wide counters the texts hold. An owner override adds
 * {@code OWNER_OVERRIDE}; the override text stays in {@code overrideReason} only.
 */
class TrustRulesTest {

    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    private static ClientView client(int completed, int noShows, int lateCancels) {
        return new ClientView(UUID.randomUUID(), "+995555000001", "Mariam", null, null, null, null, false, null, null,
                completed, noShows, lateCancels, 0);
    }

    private static void assertNumberFree(TrustView trust) {
        assertThat(trust.reasonCodes()).isNotEmpty().allSatisfy(code -> assertThat(DIGIT.matcher(code).find()).isFalse());
    }

    @Test
    void newClient() {
        TrustView trust = TrustRules.assess(client(0, 0, 0), null, null);

        assertThat(trust.level()).isEqualTo("NEW");
        assertThat(trust.reasonCodes()).containsExactly("NEW_CLIENT");
        assertNumberFree(trust);
    }

    @Test
    void noShowsMakeLowWithACodeButTheTextKeepsTheCountForTheOwner() {
        TrustView trust = TrustRules.assess(client(4, 2, 0), null, null);

        assertThat(trust.level()).isEqualTo("LOW");
        assertThat(trust.reasons()).containsExactly("2 no-shows recorded");
        assertThat(trust.reasonCodes()).containsExactly("NO_SHOWS");
        assertNumberFree(trust);
    }

    @Test
    void lateCancellationsMakeLow() {
        TrustView trust = TrustRules.assess(client(8, 0, 3), null, null);

        assertThat(trust.level()).isEqualTo("LOW");
        assertThat(trust.reasonCodes()).containsExactly("LATE_CANCELLATIONS");
    }

    @Test
    void aRegularClientIsHigh() {
        TrustView trust = TrustRules.assess(client(5, 0, 0), null, null);

        assertThat(trust.level()).isEqualTo("HIGH");
        assertThat(trust.reasonCodes()).containsExactly("REGULAR_CLIENT");
    }

    @Test
    void aReturningClientIsNormal() {
        TrustView trust = TrustRules.assess(client(7, 1, 1), null, null);

        assertThat(trust.level()).isEqualTo("NORMAL");
        assertThat(trust.reasonCodes()).containsExactly("RETURNING_CLIENT");
    }

    @Test
    void anOwnerOverrideAddsItsCodeAndKeepsTheTextApart() {
        TrustView trust = TrustRules.assess(client(4, 2, 0), "HIGH", "Pays in advance, called about the no-shows");

        assertThat(trust.effectiveLevel()).isEqualTo("HIGH");
        assertThat(trust.overrideReason()).isEqualTo("Pays in advance, called about the no-shows");
        assertThat(trust.reasonCodes()).containsExactly("NO_SHOWS", "OWNER_OVERRIDE");
        assertNumberFree(trust);
    }
}
