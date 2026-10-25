#!/bin/sh
# Cadence backup service. Dumps BOTH databases of the production stack on a schedule.
#
#   backup.sh loop   (default) one backup right away, then one every BACKUP_INTERVAL; a failed run is logged, retried
#                    after BACKUP_RETRY_SECONDS (300) and the container is unhealthy until one succeeds (healthcheck.sh)
#   backup.sh once   a single backup; exit status 0 = complete backup written, non-zero = failed (use for cron/CI)
#
# One run writes a directory $BACKUP_ROOT/<UTC timestamp>/ (e.g. 20261025T101500Z) with:
#   cadence.dump          pg_dump -Fc of the application database (TimescaleDB), snapshot-consistent
#   keycloak.dump         pg_dump -Fc of Keycloak's database
#   cadence-roles.sql     pg_dumpall --roles-only --no-role-passwords of the application DB server
#   cadence.manifest      row counts / Flyway version / hypertables / RLS / job as of the same snapshot as the dump
#   keycloak.manifest     row counts as of the same snapshot as the dump
#   SHA256SUMS            checksums of the files above (verified by scripts/restore-drill.sh)
# The directory is written as .tmp-<timestamp> and renamed only when everything succeeded, so a directory with a
# timestamp name is always a complete backup. Backups older than BACKUP_RETENTION_DAYS are deleted after a successful
# run (the newest BACKUP_KEEP_MIN are always kept).
#
# Disk space: before every dump the free space of BACKUP_ROOT must be at least BACKUP_MIN_FREE_MB (and at least the size
# of the newest backup). If it is not, old backups are pruned FIRST (by retention, then oldest first down to
# BACKUP_KEEP_MIN) and the run fails with a clear message when that is still not enough. A full disk therefore cannot
# jam the service for good (pruning used to happen only after a success), and it never takes the newest backups away.
# BACKUP_ROOT should be on another disk/volume than the databases: a full backup disk must not stop TimescaleDB.
#
# Failure handling: backup_once runs inside `if`, where `set -e` does not apply, so every write below is checked
# explicitly. A run is reported healthy by healthcheck.sh only if the newest complete backup directory is recent.
#
# Why --no-role-passwords: the roles file would otherwise carry the SCRAM verifiers of the schema owner and of
# cadence_app, material for an offline attack that travels with every offsite copy. The passwords already live in
# .env.prod (back that file up separately, encrypted). After a restore, set the runtime role password again
# (README, "Backups and restore").
#
# Why the snapshot: the manifest counts and pg_dump must describe the same instant while the application keeps
# writing. One psql session opens a REPEATABLE READ transaction and exports its snapshot; pg_dump --snapshot reads
# exactly that snapshot; the counts are taken in the same transaction.
set -eu
umask 077
# A snapshot session that died must surface as a failed backup, not as a SIGPIPE that kills this script silently.
trap '' PIPE

. /opt/backup/lib.sh

BACKUP_ROOT=${BACKUP_ROOT:-/backups}
BACKUP_INTERVAL=${BACKUP_INTERVAL:-6h}
BACKUP_RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-14}
BACKUP_KEEP_MIN=${BACKUP_KEEP_MIN:-3}
BACKUP_MIN_FREE_MB=${BACKUP_MIN_FREE_MB:-1024}
BACKUP_RETRY_SECONDS=${BACKUP_RETRY_SECONDS:-300}

APP_DB_HOST=${APP_DB_HOST:-timescaledb}
APP_DB_PORT=${APP_DB_PORT:-5432}
APP_DB_NAME=${APP_DB_NAME:-cadence}
APP_DB_USER=${APP_DB_USER:-cadence}
KC_DB_HOST=${KC_DB_HOST:-keycloak-db}
KC_DB_PORT=${KC_DB_PORT:-5432}
KC_DB_NAME=${KC_DB_NAME:-keycloak}
KC_DB_USER=${KC_DB_USER:-keycloak}
: "${APPDB_OWNER_PASSWORD:?APPDB_OWNER_PASSWORD (password of the schema owner) is required}"
: "${KCDB_PASSWORD:?KCDB_PASSWORD (password of the Keycloak database user) is required}"

SECONDS_INTERVAL=$(parse_interval "$BACKUP_INTERVAL") || {
    log "ERROR BACKUP_INTERVAL='$BACKUP_INTERVAL' is invalid (use seconds or 30m / 6h / 1d, at least 60 s)"
    exit 2
}
case "$BACKUP_RETENTION_DAYS" in '' | *[!0-9]*) log "ERROR BACKUP_RETENTION_DAYS must be a whole number of days"; exit 2 ;; esac
case "$BACKUP_KEEP_MIN" in '' | *[!0-9]*) log "ERROR BACKUP_KEEP_MIN must be a whole number"; exit 2 ;; esac
case "$BACKUP_MIN_FREE_MB" in '' | *[!0-9]*) log "ERROR BACKUP_MIN_FREE_MB must be a whole number of megabytes"; exit 2 ;; esac

# Dump one database inside an exported snapshot and write its manifest.
#   $1 label (file prefix)  $2 host  $3 port  $4 user  $5 password  $6 database  $7 "app" for the extra app manifest
dump_database() {
    label=$1 host=$2 port=$3 user=$4 password=$5 db=$6 kind=$7
    fifo=/tmp/$label.fifo
    out=$work/$label.psql.out
    err=$work/$label.psql.err
    rm -f "$fifo"
    mkfifo "$fifo" || { log "ERROR $label: cannot create $fifo"; return 1; }
    : >"$out" || { log "ERROR $label: cannot write $out (disk full?)"; return 1; }
    PGPASSWORD=$password psql -X -q -At -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U "$user" -d "$db" \
        <"$fifo" >"$out" 2>"$err" &
    psql_pid=$!
    exec 3>"$fifo"
    printf '%s\n' 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;' "SELECT 'SNAPSHOT|' || pg_export_snapshot();" >&3

    tries=0
    until grep -q '^SNAPSHOT|' "$out"; do
        tries=$((tries + 1))
        if [ "$tries" -gt 60 ] || ! kill -0 "$psql_pid" 2>/dev/null; then
            exec 3>&-
            log "ERROR $label: could not open the snapshot session: $(cat "$err")"
            return 1
        fi
        sleep 1
    done
    snapshot=$(sed -n 's/^SNAPSHOT|//p' "$out" | head -n 1)

    if ! PGPASSWORD=$password pg_dump -Fc --snapshot="$snapshot" -h "$host" -p "$port" -U "$user" -d "$db" \
            -f "$work/$label.dump"; then
        exec 3>&-
        log "ERROR $label: pg_dump failed"
        return 1
    fi

    printf '%s\n' '\i /opt/backup/manifest-tables.sql' >&3
    if [ "$kind" = app ]; then
        printf '%s\n' '\i /opt/backup/manifest-app.sql' >&3
    fi
    printf '%s\n' "SELECT 'DONE';" >&3
    tries=0
    until grep -q '^DONE$' "$out"; do
        tries=$((tries + 1))
        if [ "$tries" -gt 600 ] || ! kill -0 "$psql_pid" 2>/dev/null; then
            exec 3>&-
            log "ERROR $label: manifest query failed: $(cat "$err")"
            return 1
        fi
        sleep 1
    done
    printf '%s\n' 'COMMIT;' '\q' >&3
    exec 3>&-
    if ! wait "$psql_pid"; then
        log "ERROR $label: snapshot session ended with an error: $(cat "$err")"
        return 1
    fi
    grep -v -e '^SNAPSHOT|' -e '^DONE$' "$out" >"$work/$label.manifest" || {
        log "ERROR $label: could not write a non-empty manifest (disk full?)"
        return 1
    }
    rm -f "$fifo" "$out" "$err"

    pg_restore --list "$work/$label.dump" >/dev/null || {
        log "ERROR $label: the written dump is not a readable pg_dump archive"
        return 1
    }
    tables=$(grep -c '^count|' "$work/$label.manifest") || tables=0
    if [ "$tables" -eq 0 ]; then
        log "ERROR $label: the manifest lists no table counts"
        return 1
    fi
    log "$label: $(wc -c <"$work/$label.dump") bytes, $tables tables counted"
}

# Both servers must accept TCP connections. The compose healthcheck of the databases uses the unix socket, which is also
# open during the image's one-time initialization phase (before the real server starts), so "healthy" is not enough
# right after the first start of an empty volume.
wait_for_servers() {
    tries=0
    until pg_isready -q -h "$APP_DB_HOST" -p "$APP_DB_PORT" -U "$APP_DB_USER" -d "$APP_DB_NAME" &&
          pg_isready -q -h "$KC_DB_HOST" -p "$KC_DB_PORT" -U "$KC_DB_USER" -d "$KC_DB_NAME"; do
        tries=$((tries + 1))
        if [ "$tries" -gt 60 ]; then
            log "ERROR the databases did not accept connections within 2 minutes"
            return 1
        fi
        sleep 2
    done
}

backup_once() {
    wait_for_servers || return 1
    stamp=$(date -u +%Y%m%dT%H%M%SZ)
    final=$BACKUP_ROOT/$stamp
    work=$BACKUP_ROOT/.tmp-$stamp
    log "backup $stamp started"
    ensure_space || return 1
    rm -rf "$work"
    mkdir -p "$work" || { log "ERROR cannot create $work"; return 1; }

    dump_database cadence "$APP_DB_HOST" "$APP_DB_PORT" "$APP_DB_USER" "$APPDB_OWNER_PASSWORD" "$APP_DB_NAME" app || { rm -rf "$work"; return 1; }
    dump_database keycloak "$KC_DB_HOST" "$KC_DB_PORT" "$KC_DB_USER" "$KCDB_PASSWORD" "$KC_DB_NAME" kc || { rm -rf "$work"; return 1; }

    PGPASSWORD=$APPDB_OWNER_PASSWORD pg_dumpall --roles-only --no-role-passwords \
        -h "$APP_DB_HOST" -p "$APP_DB_PORT" -U "$APP_DB_USER" -l "$APP_DB_NAME" -f "$work/cadence-roles.sql" || {
        log "ERROR cadence-roles: pg_dumpall failed"
        rm -rf "$work"
        return 1
    }
    grep -q 'cadence_app' "$work/cadence-roles.sql" || {
        log "ERROR cadence-roles: the roles file does not contain cadence_app"
        rm -rf "$work"
        return 1
    }

    # Write the checksums, then read every file back against them: catches a short write that nothing else reported.
    (cd "$work" && sha256sum cadence.dump keycloak.dump cadence-roles.sql cadence.manifest keycloak.manifest >SHA256SUMS &&
        [ -s SHA256SUMS ] && sha256sum -c SHA256SUMS >/dev/null) || {
        log "ERROR could not write or verify SHA256SUMS (disk full?)"
        rm -rf "$work"
        return 1
    }
    mv "$work" "$final" || {
        log "ERROR could not rename $work to $final"
        rm -rf "$work"
        return 1
    }
    log "backup $stamp complete: $final"
    prune_retention
    prune_leftovers 720
    return 0
}

complete_backups() {
    ls -1d "$BACKUP_ROOT"/2*Z 2>/dev/null | sort
}

# Free space of the filesystem holding BACKUP_ROOT, whole megabytes (empty output = unknown).
free_mb() {
    df -Pk "$BACKUP_ROOT" 2>/dev/null | awk 'NR == 2 { print int($4 / 1024) }'
}

# Size of the newest complete backup, whole megabytes (0 when there is none).
newest_backup_mb() {
    newest=$(complete_backups | tail -n 1)
    if [ -z "$newest" ]; then
        echo 0
        return 0
    fi
    kb=$(du -sk "$newest" 2>/dev/null | awk '{ print $1 }')
    echo $(( (${kb:-0} + 1023) / 1024 ))
}

# Make room BEFORE dumping: free space must be >= BACKUP_MIN_FREE_MB and >= the size of the newest backup (a new one is
# about as big). Otherwise prune by retention, then delete the oldest backups one by one, never going below
# BACKUP_KEEP_MIN; fail if that is still not enough.
ensure_space() {
    need=$BACKUP_MIN_FREE_MB
    last=$(newest_backup_mb)
    [ "$last" -le "$need" ] || need=$last
    free=$(free_mb)
    if [ -z "$free" ]; then
        log "WARN cannot determine the free space of $BACKUP_ROOT; continuing without the disk-space check"
        return 0
    fi
    [ "$free" -lt "$need" ] || return 0
    log "WARN low disk space: ${free} MB free in $BACKUP_ROOT, ${need} MB required (BACKUP_MIN_FREE_MB=$BACKUP_MIN_FREE_MB); pruning old backups first"
    prune_leftovers 120
    prune_retention
    free=$(free_mb)
    while [ "${free:-0}" -lt "$need" ]; do
        count=$(complete_backups | wc -l)
        if [ "$count" -le "$BACKUP_KEEP_MIN" ]; then
            break
        fi
        oldest=$(complete_backups | head -n 1)
        log "retention: removing $oldest (low disk space; the newest $BACKUP_KEEP_MIN are kept)"
        rm -rf "$oldest" || break
        free=$(free_mb)
    done
    if [ "${free:-0}" -lt "$need" ]; then
        log "ERROR not enough disk space for a backup: ${free:-?} MB free in $BACKUP_ROOT, ${need} MB required, and only the newest $BACKUP_KEEP_MIN backups are left. Free disk space, lower BACKUP_RETENTION_DAYS or move BACKUP_DIR to a larger disk"
        return 1
    fi
    log "disk space restored to ${free} MB free"
    return 0
}

# Delete complete backups older than the retention, but keep the newest BACKUP_KEEP_MIN no matter how old.
prune_retention() {
    keep=$(complete_backups | tail -n "$BACKUP_KEEP_MIN")
    find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -name '2*Z' -mtime +"$BACKUP_RETENTION_DAYS" 2>/dev/null | while read -r old; do
        case "$keep" in *"$old"*) continue ;; esac
        log "retention: removing $old"
        rm -rf "$old"
    done
    return 0
}

# Leftovers of a run that was killed half way (older than $1 minutes, so a concurrent `once` run is left alone).
prune_leftovers() {
    find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -name '.tmp-*' -mmin +"$1" -exec rm -rf {} + 2>/dev/null || true
    return 0
}

run() {
    if backup_once; then
        rm -f "$BACKUP_ROOT/.last-failure"
        return 0
    fi
    # Best effort: on a full disk this write fails too, which is why healthcheck.sh also looks at the age of the
    # newest complete backup directory (needs no write).
    { date +%s >"$BACKUP_ROOT/.last-failure"; } 2>/dev/null ||
        log "WARN could not write $BACKUP_ROOT/.last-failure (disk full?); the healthcheck will turn unhealthy when the newest backup gets too old"
    log "ERROR backup failed; see the messages above"
    return 1
}

mkdir -p "$BACKUP_ROOT" || { log "ERROR cannot create $BACKUP_ROOT"; exit 1; }
case "${1:-loop}" in
    once)
        run
        ;;
    loop)
        trap 'log "stopping"; exit 0' TERM INT
        log "backup service: every ${BACKUP_INTERVAL}, keeping ${BACKUP_RETENTION_DAYS} days (at least ${BACKUP_KEEP_MIN} backups) in $BACKUP_ROOT"
        while true; do
            if run; then
                pause=$SECONDS_INTERVAL
            else
                # Retry sooner than a whole interval (e.g. the very first start, before the app has migrated the schema).
                pause=$BACKUP_RETRY_SECONDS
                [ "$pause" -le "$SECONDS_INTERVAL" ] || pause=$SECONDS_INTERVAL
            fi
            sleep "$pause" &
            wait $!
        done
        ;;
    *)
        log "usage: backup.sh [loop|once]"
        exit 2
        ;;
esac
