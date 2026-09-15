package com.aibusinessmanager.platform.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Schema stores naive UTC timestamps for jOOQ DDLDatabase portability. */
public final class Utc {

    private Utc() {
    }

    public static LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant toInstant(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC);
    }
}
