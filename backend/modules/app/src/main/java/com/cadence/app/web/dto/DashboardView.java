package com.cadence.app.web.dto;

import com.cadence.booking.api.AppointmentView;
import com.cadence.growth.api.GrowthSignalView;
import com.cadence.growth.api.MetricView;
import com.cadence.notification.api.OwnerTaskView;

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
