package com.cadence.app.web.dto;

import com.cadence.booking.api.AppointmentView;
import com.cadence.retention.api.TrustView;

public record AppointmentDetailView(
        AppointmentView appointment,
        TrustView trust
) {
}
