package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.AiProperties;
import com.cadence.ai.internal.drafts.DraftPreflight;
import com.cadence.ai.internal.drafts.DraftWriter;
import com.cadence.ai.internal.privacy.FreeTextMasker;
import com.cadence.ai.internal.privacy.PseudonymRegistry;
import com.cadence.booking.api.BookingService;
import com.cadence.crm.api.CrmService;
import com.cadence.growth.api.GrowthService;
import com.cadence.retention.api.RetentionService;

import java.time.Clock;
import java.util.List;

/** The seven package-private tools for tests in other packages. */
public final class TestTools {

    public static final String WITHHELD = GetClientProfileTool.WITHHELD;

    private TestTools() {
    }

    public static List<AiTool> all(
            BookingService booking,
            CrmService crm,
            GrowthService growth,
            RetentionService retention,
            FreeTextMasker masker,
            PseudonymRegistry registry,
            DraftPreflight preflight,
            DraftWriter writer,
            Clock clock,
            AiProperties properties
    ) {
        return List.of(
                new GetScheduleTool(booking, masker, properties),
                new FindFreeSlotsTool(booking),
                new GetClientProfileTool(crm, booking, retention, masker, registry, clock),
                new GetMetricsTool(growth),
                new DraftBookingTool(preflight, writer),
                new DraftRescheduleTool(preflight, writer),
                new DraftCancelTool(preflight, writer));
    }
}
