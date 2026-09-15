package com.aibusinessmanager.booking.api;

import java.util.UUID;

public record PublicBookingResult(
        AppointmentView appointment,
        String accessToken,
        String managePath
) {
}
