-- Manual replay of the six growth metrics from the booking journal (ADR 0001, PRODUCT_PLAN §12 phase 2 criterion:
-- "every metric is reproducible by hand from the event journal").
--
-- This is the written verification procedure. It reads only appointment_event (the journal), the schedule tables
-- (weekly_schedule, schedule_exception, specialist.active) and the tenant's time zone; it never reads appointment
-- or metric_snapshot. MetricsJournalReplayIT runs it and requires equality with the stored snapshots (1e-9).
--
-- How to run by hand, as the owner role (it sees every tenant, so the tenant is a parameter):
--   1. Pick a snapshot: SELECT DISTINCT created_at, window_start, window_end FROM metric_snapshot
--      WHERE tenant_id = '<tenant>' ORDER BY created_at DESC;
--   2. psql -v tenant_id=<tenant> -v as_of='<created_at>' -v window_start=<yyyy-mm-dd> -v window_end=<yyyy-mm-dd>
--           -f metrics-replay.sql
--   3. Compare each row with metric_snapshot rows of that created_at and window (specialist_id NULL = tenant).
--
-- Definitions (ADR 0001 §4): W = window dates in the tenant zone Z; Wi = [start of window_start, start of the day after
-- window_end), naive UTC like every stored timestamp; T = as_of; facts = visits after folding events with
-- created_at <= T in (created_at, id) order: CREATED gives the facts, the last status event sets the status, the last
-- RESCHEDULED the start (end = start + CREATED duration), the last CLIENT_CHANGED the client. A visit whose first event
-- is not CREATED has no fact. "Minute m of date d" is start of d in Z + m minutes on the time line. Completed visits of
-- one client on one date in Z are one visit day (REPEAT_RATE, RETURN_INTERVAL, CHURN_RISK count and space visit days).
-- The tenant slice uses the sentinel specialist id 00000000-0000-0000-0000-000000000000 internally (output: NULL).

WITH params AS (
    SELECT CAST(:'tenant_id' AS uuid)      AS tenant_id,
           CAST(:'as_of' AS timestamp)     AS as_of,
           CAST(:'window_start' AS date)   AS ws,
           CAST(:'window_end' AS date)     AS we,
           t.timezone                      AS zone
    FROM tenant t
    WHERE t.id = CAST(:'tenant_id' AS uuid)
),
b AS (
    SELECT p.*,
           (p.ws::timestamp AT TIME ZONE p.zone) AT TIME ZONE 'UTC'       AS wi_start,
           ((p.we + 1)::timestamp AT TIME ZONE p.zone) AT TIME ZONE 'UTC' AS wi_end,
           ((p.as_of AT TIME ZONE 'UTC') AT TIME ZONE p.zone)::date       AS day_t
    FROM params p
),
-- ---- the fold ----
ev AS (
    SELECT e.id, e.appointment_id, e.event_type, e.payload::jsonb AS p, e.created_at
    FROM appointment_event e, b
    WHERE e.tenant_id = b.tenant_id
      AND e.created_at <= b.as_of
),
first_event AS (
    SELECT DISTINCT ON (appointment_id) *
    FROM ev
    ORDER BY appointment_id, created_at, id
),
created AS (
    SELECT appointment_id,
           (p ->> 'specialistId')::uuid                               AS specialist_id,
           (p ->> 'serviceId')::uuid                                  AS service_id,
           (p ->> 'clientId')::uuid                                   AS client_id,
           p ->> 'status'                                             AS status,
           ((p ->> 'serviceStart')::timestamptz AT TIME ZONE 'UTC')   AS service_start,
           ((p ->> 'serviceEnd')::timestamptz AT TIME ZONE 'UTC')     AS service_end,
           (p ->> 'priceMinor')::int                                  AS price_minor,
           (p ->> 'discountMinor')::int                               AS discount_minor,
           (p ->> 'durationMinutes')::int                             AS duration_minutes
    FROM first_event
    WHERE event_type = 'CREATED'
),
last_status AS (
    SELECT DISTINCT ON (appointment_id) appointment_id, p ->> 'to' AS status
    FROM ev
    WHERE event_type IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED')
    ORDER BY appointment_id, created_at DESC, id DESC
),
last_move AS (
    SELECT DISTINCT ON (appointment_id) appointment_id, ((p ->> 'to')::timestamptz AT TIME ZONE 'UTC') AS service_start
    FROM ev
    WHERE event_type = 'RESCHEDULED'
    ORDER BY appointment_id, created_at DESC, id DESC
),
last_client AS (
    SELECT DISTINCT ON (appointment_id) appointment_id, (p ->> 'to')::uuid AS client_id
    FROM ev
    WHERE event_type = 'CLIENT_CHANGED'
    ORDER BY appointment_id, created_at DESC, id DESC
),
facts AS (
    SELECT c.appointment_id,
           c.specialist_id,
           c.service_id,
           COALESCE(lc.client_id, c.client_id)                                           AS client_id,
           COALESCE(ls.status, c.status)                                                 AS status,
           COALESCE(lm.service_start, c.service_start)                                   AS service_start,
           CASE WHEN lm.service_start IS NULL THEN c.service_end
                ELSE lm.service_start + make_interval(mins => c.duration_minutes) END    AS service_end,
           c.price_minor,
           c.discount_minor,
           c.duration_minutes
    FROM created c
    LEFT JOIN last_status ls ON ls.appointment_id = c.appointment_id
    LEFT JOIN last_move lm ON lm.appointment_id = c.appointment_id
    LEFT JOIN last_client lc ON lc.appointment_id = c.appointment_id
),
-- ---- slices: the tenant and every specialist active now ----
slices AS (
    SELECT '00000000-0000-0000-0000-000000000000'::uuid AS sid
    UNION ALL
    SELECT s.id FROM specialist s, b WHERE s.tenant_id = b.tenant_id AND s.active
),
sf AS (
    SELECT sl.sid, f.*,
           f.service_start >= b.wi_start AND f.service_start < b.wi_end                  AS in_w,
           ((f.service_start AT TIME ZONE 'UTC') AT TIME ZONE b.zone)::date              AS day
    FROM slices sl
    CROSS JOIN b
    JOIN facts f ON sl.sid = '00000000-0000-0000-0000-000000000000'::uuid OR f.specialist_id = sl.sid
),
-- ---- 1. SLOT_UTILIZATION ----
members AS (
    SELECT sl.sid, s.id AS specialist_id
    FROM slices sl
    JOIN specialist s ON s.active
        AND s.tenant_id = (SELECT tenant_id FROM b)
        AND (sl.sid = '00000000-0000-0000-0000-000000000000'::uuid OR s.id = sl.sid)
),
booked AS (
    SELECT sl.sid,
           COALESCE(SUM(GREATEST(0, floor(extract(epoch FROM
               (LEAST(f.service_end, b.wi_end) - GREATEST(f.service_start, b.wi_start))) / 60))), 0) AS minutes
    FROM slices sl
    CROSS JOIN b
    LEFT JOIN sf f ON f.sid = sl.sid
        AND f.status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'NO_SHOW')
        AND f.specialist_id IN (SELECT m.specialist_id FROM members m WHERE m.sid = sl.sid)
    GROUP BY sl.sid
),
days AS (
    SELECT d::date AS d FROM b, generate_series(b.ws::timestamp, b.we::timestamp, interval '1 day') d
),
-- The exception that applies to a date: only DAY_OFF or a SHIFT with both minutes; DAY_OFF wins, then the SHIFT
-- with the earliest start, then end. Other kinds never apply. Same rule as WorkingHours.appliedException.
applied AS (
    SELECT DISTINCT ON (x.specialist_id, x.exception_date) x.*
    FROM schedule_exception x, b
    WHERE x.tenant_id = b.tenant_id
      AND (x.kind = 'DAY_OFF' OR (x.kind = 'SHIFT' AND x.start_minute IS NOT NULL AND x.end_minute IS NOT NULL))
    ORDER BY x.specialist_id, x.exception_date, (x.kind <> 'DAY_OFF'), x.start_minute NULLS FIRST, x.end_minute NULLS FIRST
),
windows AS (
    SELECT m.sid, d.d, w.start_minute, w.end_minute
    FROM members m
    CROSS JOIN days d
    JOIN weekly_schedule w ON w.specialist_id = m.specialist_id AND w.weekday = extract(isodow FROM d.d)
    WHERE NOT EXISTS (SELECT 1 FROM applied a WHERE a.specialist_id = m.specialist_id AND a.exception_date = d.d)
    UNION ALL
    SELECT m.sid, d.d, a.start_minute, a.end_minute
    FROM members m
    CROSS JOIN days d
    JOIN applied a ON a.specialist_id = m.specialist_id AND a.exception_date = d.d AND a.kind = 'SHIFT'
),
available AS (
    SELECT sl.sid,
           COALESCE(SUM(GREATEST(0, floor(extract(epoch FROM
               (((w.d::timestamp AT TIME ZONE b.zone) + make_interval(mins => w.end_minute))
                - ((w.d::timestamp AT TIME ZONE b.zone) + make_interval(mins => w.start_minute)))) / 60))), 0) AS minutes
    FROM slices sl
    CROSS JOIN b
    LEFT JOIN windows w ON w.sid = sl.sid
    GROUP BY sl.sid
),
-- ---- 2. REPEAT_RATE and 3. RETURN_INTERVAL: completed visits of a client on one date are one visit day ----
completed AS (
    SELECT * FROM sf WHERE status = 'COMPLETED'
),
p_clients AS (
    SELECT DISTINCT sid, client_id FROM completed WHERE in_w
),
days_before_end AS (
    SELECT DISTINCT c.sid, c.client_id, c.day
    FROM completed c, b
    WHERE c.service_start < b.wi_end
),
before_end AS (
    SELECT sid, client_id, count(*) AS n
    FROM days_before_end
    GROUP BY sid, client_id
),
repeat AS (
    SELECT sl.sid,
           count(p.client_id)                          AS p,
           count(p.client_id) FILTER (WHERE be.n >= 2) AS r
    FROM slices sl
    LEFT JOIN p_clients p ON p.sid = sl.sid
    LEFT JOIN before_end be ON be.sid = p.sid AND be.client_id = p.client_id
    GROUP BY sl.sid
),
gaps_before_end AS (
    SELECT sid, client_id, day - lag(day) OVER (PARTITION BY sid, client_id ORDER BY day) AS gap
    FROM days_before_end
),
personal AS (
    SELECT g.sid, g.client_id, percentile_cont(0.5) WITHIN GROUP (ORDER BY g.gap) AS m
    FROM gaps_before_end g
    JOIN p_clients p ON p.sid = g.sid AND p.client_id = g.client_id
    WHERE g.gap IS NOT NULL
    GROUP BY g.sid, g.client_id
),
return_interval AS (
    SELECT sl.sid, count(pe.m) AS n, percentile_cont(0.5) WITHIN GROUP (ORDER BY pe.m) AS v
    FROM slices sl
    LEFT JOIN personal pe ON pe.sid = sl.sid
    GROUP BY sl.sid
),
-- ---- 4. NO_SHOW_RATE ----
no_show AS (
    SELECT sl.sid,
           count(f.appointment_id)                                   AS d,
           count(f.appointment_id) FILTER (WHERE f.status = 'NO_SHOW') AS n
    FROM slices sl
    CROSS JOIN b
    LEFT JOIN sf f ON f.sid = sl.sid
        AND f.in_w
        AND f.service_start < b.as_of
        AND f.status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'NO_SHOW')
    GROUP BY sl.sid
),
-- ---- 5. REVENUE_PER_HOUR ----
revenue AS (
    SELECT sl.sid,
           COALESCE(SUM(c.price_minor::bigint - c.discount_minor), 0) AS value_minor,
           COALESCE(SUM(c.duration_minutes), 0)                       AS minutes
    FROM slices sl
    LEFT JOIN completed c ON c.sid = sl.sid AND c.in_w
    GROUP BY sl.sid
),
-- ---- 6. CHURN_RISK (at T) ----
completed_t AS (
    SELECT c.* FROM completed c, b WHERE c.service_start <= b.as_of
),
days_t AS (
    SELECT DISTINCT sid, client_id, day FROM completed_t
),
gaps_t AS (
    SELECT sid, client_id, day - lag(day) OVER (PARTITION BY sid, client_id ORDER BY day) AS gap
    FROM days_t
),
typical AS (
    SELECT sid, client_id, percentile_cont(0.5) WITHIN GROUP (ORDER BY gap) AS days
    FROM gaps_t
    WHERE gap IS NOT NULL
    GROUP BY sid, client_id
),
slice_typical AS (
    SELECT sid, percentile_cont(0.5) WITHIN GROUP (ORDER BY days) AS days
    FROM typical
    GROUP BY sid
),
k AS (
    SELECT c.sid, c.client_id, max(c.day) AS last_day
    FROM completed_t c
    GROUP BY c.sid, c.client_id
    HAVING max(c.service_start) >= (SELECT as_of - interval '365 days' FROM b)
),
assessed AS (
    SELECT k.sid, k.client_id, (b.day_t - k.last_day) AS days, COALESCE(t.days, st.days) AS typical
    FROM k
    CROSS JOIN b
    LEFT JOIN typical t ON t.sid = k.sid AND t.client_id = k.client_id
    LEFT JOIN slice_typical st ON st.sid = k.sid
),
churn AS (
    SELECT sl.sid,
           count(a.client_id) FILTER (WHERE a.typical IS NOT NULL)                            AS k,
           count(a.client_id) FILTER (WHERE a.typical IS NOT NULL AND a.days >= 2 * a.typical) AS high
    FROM slices sl
    LEFT JOIN assessed a ON a.sid = sl.sid
    GROUP BY sl.sid
),
-- ---- result: one row per slice and metric; value NULL below the minimum sample ----
result AS (
    SELECT sid, 'SLOT_UTILIZATION' AS metric_key,
           CASE WHEN av.minutes >= 1 THEN bk.minutes::float8 / av.minutes END AS value_numeric,
           av.minutes::int AS sample_size, av.minutes < 1 AS insufficient_data
    FROM booked bk JOIN available av USING (sid)
    UNION ALL
    SELECT sid, 'REPEAT_RATE', CASE WHEN p >= 10 THEN r::float8 / p END, p::int, p < 10 FROM repeat
    UNION ALL
    SELECT sid, 'RETURN_INTERVAL', CASE WHEN n >= 3 THEN v END, n::int, n < 3 FROM return_interval
    UNION ALL
    SELECT sid, 'NO_SHOW_RATE', CASE WHEN d >= 5 THEN n::float8 / d END, d::int, d < 5 FROM no_show
    UNION ALL
    SELECT sid, 'REVENUE_PER_HOUR', CASE WHEN minutes >= 1 THEN value_minor::float8 / (minutes::float8 / 60.0) END,
           minutes::int, minutes < 1
    FROM revenue
    UNION ALL
    SELECT sid, 'CHURN_RISK', CASE WHEN k >= 5 THEN high::float8 / k END, k::int, k < 5 FROM churn
)
SELECT NULLIF(sid, '00000000-0000-0000-0000-000000000000'::uuid) AS specialist_id,
       metric_key, value_numeric, sample_size, insufficient_data
FROM result
ORDER BY specialist_id NULLS FIRST, metric_key;
