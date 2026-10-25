package com.cadence.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@code metrics-replay.sql} (journal and schedule only, ADR 0001) for every window of one recalculation and
 * asserts equality with the stored {@code metric_snapshot} rows within 1e-9. Shared by the replay and seed ITs.
 */
final class MetricsReplay {

    static final double EPS = 1e-9;

    /** How many snapshot rows were compared, and how many of them had a value (enough data). */
    record Outcome(int compared, int withValue) {
    }

    interface Connections {
        Connection open() throws SQLException;
    }

    private record Row(UUID specialistId, String key, Double value, int sample, boolean insufficient) {
    }

    private MetricsReplay() {
    }

    static Outcome assertEqualsSnapshots(Connections owner, UUID tenant, Instant asOf) throws Exception {
        LocalDateTime at = LocalDateTime.ofInstant(asOf, ZoneOffset.UTC);
        Map<List<LocalDate>, List<Row>> stored = new HashMap<>();
        try (Connection c = owner.open();
             PreparedStatement st = c.prepareStatement("""
                     select specialist_id, metric_key, value_numeric, sample_size, insufficient_data, window_start, window_end
                     from metric_snapshot where tenant_id = ? and created_at = ?""")) {
            st.setObject(1, tenant);
            st.setTimestamp(2, Timestamp.valueOf(at));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    List<LocalDate> window = List.of(rs.getObject("window_start", LocalDate.class),
                            rs.getObject("window_end", LocalDate.class));
                    stored.computeIfAbsent(window, k -> new ArrayList<>()).add(row(rs));
                }
            }
        }
        assertThat(stored).as("two windows per recalculation").hasSize(2);
        String sql = replaySql();
        int compared = 0;
        int withValue = 0;
        for (Map.Entry<List<LocalDate>, List<Row>> window : stored.entrySet()) {
            String bound = sql
                    .replace(":'tenant_id'", "'" + tenant + "'")
                    .replace(":'as_of'", "'" + at + "'")
                    .replace(":'window_start'", "'" + window.getKey().get(0) + "'")
                    .replace(":'window_end'", "'" + window.getKey().get(1) + "'");
            Map<String, Row> replayed = new HashMap<>();
            try (Connection c = owner.open(); var st = c.createStatement(); ResultSet rs = st.executeQuery(bound)) {
                while (rs.next()) {
                    Row r = row(rs);
                    replayed.put(r.specialistId() + "/" + r.key(), r);
                }
            }
            assertThat(replayed).as("replayed rows of " + window.getKey()).hasSameSizeAs(window.getValue());
            for (Row snapshot : window.getValue()) {
                Row replay = replayed.get(snapshot.specialistId() + "/" + snapshot.key());
                String what = snapshot.key() + " of " + (snapshot.specialistId() == null ? "tenant" : snapshot.specialistId())
                        + " in " + window.getKey() + " at " + at;
                assertThat(replay).as(what).isNotNull();
                assertThat(replay.insufficient()).as(what + " insufficient").isEqualTo(snapshot.insufficient());
                assertThat(replay.sample()).as(what + " sample").isEqualTo(snapshot.sample());
                if (snapshot.value() == null) {
                    assertThat(replay.value()).as(what).isNull();
                } else {
                    assertThat(replay.value()).as(what).isNotNull();
                    assertThat(Math.abs(replay.value() - snapshot.value())).as(what + " value " + snapshot.value()
                            + " vs replay " + replay.value()).isLessThanOrEqualTo(EPS);
                    withValue++;
                }
                compared++;
            }
        }
        return new Outcome(compared, withValue);
    }

    private static String replaySql() throws IOException {
        try (InputStream in = MetricsReplay.class.getResourceAsStream("/metrics-replay.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Row row(ResultSet rs) throws SQLException {
        double v = rs.getDouble("value_numeric");
        Double value = rs.wasNull() ? null : v;
        return new Row(rs.getObject("specialist_id", UUID.class), rs.getString("metric_key"), value,
                rs.getInt("sample_size"), rs.getBoolean("insufficient_data"));
    }
}
