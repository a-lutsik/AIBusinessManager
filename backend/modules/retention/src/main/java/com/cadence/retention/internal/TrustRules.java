package com.cadence.retention.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.retention.api.TrustView;

import java.util.ArrayList;
import java.util.List;

/**
 * The trust level of a client from the CRM aggregates, with the reasons for it; pure, no I/O. Each reason comes twice:
 * as a text for the owner, which may count salon-wide visits, and as a number-free code that a master may see
 * (decision B).
 */
final class TrustRules {

    private TrustRules() {
    }

    static TrustView assess(ClientView client, String overrideLevel, String overrideReason) {
        List<String> reasons = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        String level;
        if (client.completedVisitCount() == 0) {
            level = "NEW";
            reasons.add("No completed visits yet");
            codes.add("NEW_CLIENT");
        } else {
            int recentNoShows = client.noShowCount();
            int lateCancels = client.lateCancelCount();
            if (recentNoShows >= 2 || (recentNoShows >= 1 && client.completedVisitCount() <= 5)) {
                level = "LOW";
                reasons.add(recentNoShows + " no-shows recorded");
                codes.add("NO_SHOWS");
            } else if (lateCancels >= 2) {
                level = "LOW";
                reasons.add(lateCancels + " late cancellations");
                codes.add("LATE_CANCELLATIONS");
            } else if (recentNoShows == 0 && lateCancels == 0 && client.completedVisitCount() >= 3) {
                level = "HIGH";
                reasons.add(client.completedVisitCount() + " completed visits, 0 no-shows");
                codes.add("REGULAR_CLIENT");
            } else {
                level = "NORMAL";
                reasons.add(client.completedVisitCount() + " completed visits");
                codes.add("RETURNING_CLIENT");
            }
        }
        if (overrideLevel != null) {
            codes.add("OWNER_OVERRIDE");
        }
        return new TrustView(client.id(), level, reasons, overrideLevel, overrideReason, List.copyOf(codes));
    }
}
