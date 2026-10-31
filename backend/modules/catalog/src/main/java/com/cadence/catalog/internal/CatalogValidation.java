package com.cadence.catalog.internal;

import com.cadence.catalog.api.MasterServiceView;
import com.cadence.catalog.api.ScheduleExceptionView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.catalog.api.WorkingHours;
import com.cadence.platform.error.DomainException;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** What the catalog accepts: the owner's input is refused here with a code, before it reaches the database. */
final class CatalogValidation {

    private static final int MINUTES_IN_DAY = 1440;
    private static final int MAX_NAME = 256;
    private static final int MAX_DESCRIPTION = 2048;
    private static final int MAX_NOTE = 512;
    private static final int MAX_KEYCLOAK_USER_ID = 128;
    /** Far above any real week; also keeps the audit row of a replacement within its column. */
    private static final int MAX_WEEK_INTERVALS = 70;
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private CatalogValidation() {
    }

    static void service(ServiceView service) {
        name(service.name());
        if (service.description() != null && service.description().length() > MAX_DESCRIPTION) {
            throw DomainException.badRequest("INVALID_DESCRIPTION", "Description is longer than " + MAX_DESCRIPTION + " characters");
        }
        duration(service.durationMinutes());
        price(service.priceMinor());
        buffer(service.bufferBeforeMinutes());
        buffer(service.bufferAfterMinutes());
        color(service.color());
    }

    static void specialist(SpecialistView specialist) {
        name(specialist.displayName());
        color(specialist.calendarColor());
        String link = specialist.keycloakUserId();
        if (link != null && (link.isBlank() || link.length() > MAX_KEYCLOAK_USER_ID)) {
            throw DomainException.badRequest("INVALID_KEYCLOAK_USER_ID", "keycloakUserId is blank or too long");
        }
    }

    static void matrixRow(MasterServiceView row) {
        if (row.serviceId() == null) {
            throw DomainException.badRequest("INVALID_MATRIX_ROW", "serviceId is required");
        }
        if (row.durationMinutesOverride() != null) {
            duration(row.durationMinutesOverride());
        }
        if (row.priceMinorOverride() != null) {
            price(row.priceMinorOverride());
        }
        if (row.bufferBeforeMinutesOverride() != null) {
            buffer(row.bufferBeforeMinutesOverride());
        }
        if (row.bufferAfterMinutesOverride() != null) {
            buffer(row.bufferAfterMinutesOverride());
        }
    }

    static void weeklySchedule(List<WeeklyInterval> intervals) {
        if (intervals.size() > MAX_WEEK_INTERVALS) {
            throw DomainException.badRequest("INVALID_SCHEDULE_INTERVAL", "A week holds at most " + MAX_WEEK_INTERVALS + " intervals");
        }
        for (WeeklyInterval interval : intervals) {
            if (interval == null || interval.weekday() < 1 || interval.weekday() > 7
                    || !isWindow(interval.startMinute(), interval.endMinute())) {
                throw DomainException.badRequest("INVALID_SCHEDULE_INTERVAL",
                        "An interval needs a weekday 1..7 and minutes 0..1440 with the start before the end");
            }
        }
        List<WeeklyInterval> ordered = intervals.stream()
                .sorted(Comparator.comparingInt(WeeklyInterval::weekday).thenComparingInt(WeeklyInterval::startMinute))
                .toList();
        for (int i = 1; i < ordered.size(); i++) {
            WeeklyInterval previous = ordered.get(i - 1);
            WeeklyInterval next = ordered.get(i);
            if (previous.weekday() == next.weekday() && previous.endMinute() > next.startMinute()) {
                throw DomainException.badRequest("SCHEDULE_OVERLAP", "Intervals of one day must not overlap");
            }
        }
    }

    static void exception(ScheduleExceptionView exception) {
        if (exception.date() == null) {
            throw DomainException.badRequest("INVALID_EXCEPTION_DATE", "date is required");
        }
        boolean dayOff = WorkingHours.DAY_OFF.equals(exception.kind());
        if (!dayOff && !WorkingHours.SHIFT.equals(exception.kind())) {
            throw DomainException.badRequest("INVALID_EXCEPTION_KIND", "kind must be DAY_OFF or SHIFT");
        }
        Integer start = exception.startMinute();
        Integer end = exception.endMinute();
        boolean minutesValid = dayOff ? start == null && end == null : start != null && end != null && isWindow(start, end);
        if (!minutesValid) {
            throw DomainException.badRequest("INVALID_EXCEPTION_MINUTES",
                    "DAY_OFF takes no minutes; SHIFT needs minutes 0..1440 with the start before the end");
        }
        if (exception.note() != null && exception.note().length() > MAX_NOTE) {
            throw DomainException.badRequest("INVALID_NOTE", "Note is longer than " + MAX_NOTE + " characters");
        }
    }

    private static boolean isWindow(int start, int end) {
        return start >= 0 && end <= MINUTES_IN_DAY && start < end;
    }

    private static void name(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME) {
            throw DomainException.badRequest("INVALID_NAME", "Name is required, up to " + MAX_NAME + " characters");
        }
    }

    private static void duration(int minutes) {
        if (minutes <= 0 || minutes > MINUTES_IN_DAY) {
            throw DomainException.badRequest("INVALID_DURATION", "Duration must be 1..1440 minutes");
        }
    }

    private static void price(int minor) {
        if (minor < 0) {
            throw DomainException.badRequest("INVALID_PRICE", "Price must not be negative");
        }
    }

    private static void buffer(int minutes) {
        if (minutes < 0 || minutes > MINUTES_IN_DAY) {
            throw DomainException.badRequest("INVALID_BUFFER", "A buffer must be 0..1440 minutes");
        }
    }

    private static void color(String color) {
        if (color == null || !COLOR.matcher(color).matches()) {
            throw DomainException.badRequest("INVALID_COLOR", "Color must look like #0f766e");
        }
    }
}
