package com.aibusinessmanager.app.web.dto;

import com.aibusinessmanager.booking.api.AppointmentView;
import com.aibusinessmanager.growth.api.GrowthSignalView;
import com.aibusinessmanager.growth.api.MetricView;
import com.aibusinessmanager.notification.api.OwnerTaskView;

import java.time.Instant;
import java.util.List;

public record DashboardView(
        Instant snapshotAt,
        List<AppointmentView> today,
        List<MetricView> metrics,
        List<OwnerTaskView> tasks,
        List<GrowthSignalView> signals
) {
}
