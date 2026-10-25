-- Row counts of every table in schema public, one line per table: count|public.<table>|<n>.
-- Executed twice: inside the backup's snapshot transaction (so the numbers match the dump exactly) and by
-- scripts/restore-drill.sh on the restored copy; the drill compares the two outputs.
-- Hypertables are counted through the hypertable (chunks live in cadence_chunks and are not listed here).
SELECT 'count|' || schemaname || '.' || tablename || '|' ||
       (xpath('/row/c/text()',
              query_to_xml(format('SELECT count(*) AS c FROM %I.%I', schemaname, tablename), false, true, '')))[1]::text
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY tablename;
SELECT 'info|server_version|' || current_setting('server_version');
