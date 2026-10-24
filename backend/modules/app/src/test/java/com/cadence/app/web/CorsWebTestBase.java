package com.cadence.app.web;

import com.cadence.ai.api.AiAssistantService;
import com.cadence.app.ratelimit.ClientIpResolver;
import com.cadence.app.ratelimit.PublicRateLimiter;
import com.cadence.app.usecase.ClientMergeService;
import com.cadence.booking.api.BookingService;
import com.cadence.catalog.api.CatalogService;
import com.cadence.crm.api.CrmService;
import com.cadence.growth.api.GrowthService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.retention.api.RetentionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Collaborators the web slice needs (same set as {@link AppApiSecurityWebTest}); none is exercised by CORS tests. */
abstract class CorsWebTestBase {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TenantDirectory tenants;
    @MockitoBean
    CatalogService catalogService;
    @MockitoBean
    BookingService bookingService;
    @MockitoBean
    CrmService crmService;
    @MockitoBean
    RetentionService retentionService;
    @MockitoBean
    GrowthService growthService;
    @MockitoBean
    AiAssistantService aiAssistantService;
    @MockitoBean
    OwnerTaskService ownerTaskService;
    @MockitoBean
    ClientMergeService clientMergeService;
    @MockitoBean
    PublicRateLimiter publicRateLimiter;
    @MockitoBean
    ClientIpResolver clientIpResolver;
}
