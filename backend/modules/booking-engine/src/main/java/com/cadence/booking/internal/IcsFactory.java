package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentView;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class IcsFactory {

    private static final DateTimeFormatter ICS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private IcsFactory() {
    }

    static String render(AppointmentView appointment, String specialistName, String tenantName) {
        String start = ICS.format(appointment.serviceStart().atZone(ZoneOffset.UTC));
        String end = ICS.format(appointment.serviceEnd().atZone(ZoneOffset.UTC));
        String summary = escape(appointment.serviceNameSnapshot() + " — " + tenantName);
        return """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Cadence//Booking//EN
                CALSCALE:GREGORIAN
                METHOD:PUBLISH
                BEGIN:VEVENT
                UID:%s@cadence
                DTSTAMP:%s
                DTSTART:%s
                DTEND:%s
                SUMMARY:%s
                DESCRIPTION:%s
                END:VEVENT
                END:VCALENDAR
                """.formatted(
                appointment.id(),
                ICS.format(java.time.Instant.now().atZone(ZoneOffset.UTC)),
                start,
                end,
                summary,
                escape("Specialist: " + specialistName)
        ).replace("\n", "\r\n");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,");
    }
}
