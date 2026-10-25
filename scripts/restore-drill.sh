#!/usr/bin/env bash
# Cadence restore drill: restore a backup made by the `backup` service into THROWAWAY database containers, compare the
# result with what was recorded at backup time, print PASS/FAIL, exit 0 only on PASS. Touches no production container,
# volume or network: the containers run with --network none and are removed (with their volumes) on every exit path.
#
#   scripts/restore-drill.sh [latest | <timestamp> | <path to a backup directory>]
#
# Environment (all optional):
#   BACKUP_DIR     directory holding the timestamped backups (default: ./backups next to docker-compose.prod.yml)
#   DRILL_PREFIX   name prefix of the throwaway containers (default: cadence-drill)
#   TS_IMAGE       TimescaleDB image for the application database (default: the one in docker-compose.prod.yml)
#   PG_IMAGE       PostgreSQL image for the Keycloak database (default: the one in docker-compose.prod.yml)
#
# What is restored, following the TimescaleDB logical restore procedure
# (https://www.tigerdata.com/docs/self-hosted/latest/backup-and-restore/logical-backup, "Entire database"):
#   roles file -> CREATE DATABASE -> CREATE EXTENSION timescaledb -> SELECT timescaledb_pre_restore()
#   -> pg_restore (no -j, as the procedure requires) -> SELECT timescaledb_post_restore()
# and Keycloak's database with a plain pg_restore.
#
# What is verified: SHA256SUMS; TimescaleDB extension version equals the one at backup time; restore without unexpected
# errors; Flyway version, row counts of every public table, hypertable/chunk counts, RLS table/policy counts and the
# V11 job equal the manifest taken in the dump's own snapshot; no chunk outside schema cadence_chunks; cadence_app is
# still subject to RLS (no tenant context -> 0 rows, with the tenant context -> that tenant's rows); Keycloak counts.

set -u -o pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
COMPOSE_FILE=$REPO_ROOT/docker-compose.prod.yml
SQL_DIR=$REPO_ROOT/docker/backup

BACKUP_DIR=${BACKUP_DIR:-$REPO_ROOT/backups}
DRILL_PREFIX=${DRILL_PREFIX:-cadence-drill}
TS_IMAGE=${TS_IMAGE:-$(sed -n 's/^ *image: *\(timescale\/timescaledb:[^ ]*\).*/\1/p' "$COMPOSE_FILE" | head -n 1)}
PG_IMAGE=${PG_IMAGE:-$(sed -n 's/^ *image: *\(postgres:[^ ]*\).*/\1/p' "$COMPOSE_FILE" | head -n 1)}

FAILS=0
WARNS=0
pass() { printf '[PASS] %s\n' "$*"; }
fail() { printf '[FAIL] %s\n' "$*"; FAILS=$((FAILS + 1)); }
warn() { printf '[WARN] %s\n' "$*"; WARNS=$((WARNS + 1)); }
info() { printf '       %s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }

usage() {
    sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
}

[ $# -le 1 ] || usage
case "${1:-latest}" in -h | --help) usage ;; esac
command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 2; }
[ -n "$TS_IMAGE" ] && [ -n "$PG_IMAGE" ] || { echo "cannot read the database images from $COMPOSE_FILE" >&2; exit 2; }

# ---- locate the backup -----------------------------------------------------------------------------------------------
target=${1:-latest}
if [ "$target" = latest ]; then
    latest=$(ls -1d "$BACKUP_DIR"/2*Z 2>/dev/null | sort | tail -n 1)
    [ -n "$latest" ] || { echo "no backup found in $BACKUP_DIR" >&2; exit 2; }
    SRC=$latest
elif [ -d "$target" ]; then
    SRC=$(cd "$target" && pwd)
elif [ -d "$BACKUP_DIR/$target" ]; then
    SRC=$(cd "$BACKUP_DIR/$target" && pwd)
else
    echo "backup '$target' not found (neither a directory nor a timestamp below $BACKUP_DIR)" >&2
    exit 2
fi
for f in cadence.dump keycloak.dump cadence-roles.sql cadence.manifest keycloak.manifest SHA256SUMS; do
    [ -e "$SRC/$f" ] || { echo "$SRC is not a complete backup: $f is missing" >&2; exit 2; }
done

RUN_ID="$(date +%s)-$$"
APP_C="$DRILL_PREFIX-app-$RUN_ID"
KC_C="$DRILL_PREFIX-kc-$RUN_ID"
LABEL="cadence.restore-drill=$RUN_ID"
rand() { openssl rand -hex 16; }
OWNER_PW=$(rand)
KC_PW=$(rand)
APPRUN_PW=$(rand)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/cadence-drill.XXXXXX")

cleanup() {
    docker rm -f -v "$APP_C" "$KC_C" >/dev/null 2>&1
    rm -rf "$WORK"
}
trap cleanup EXIT
trap 'echo; echo "interrupted"; exit 130' INT TERM

printf 'Cadence restore drill\n'
info "backup:     $SRC"
info "containers: $APP_C ($TS_IMAGE), $KC_C ($PG_IMAGE), no network, removed on exit"

# ---- helpers ---------------------------------------------------------------------------------------------------------
# psql/pg_restore as root inside the throwaway container: root can read the 0600 files whatever uid wrote them.
app_exec() { docker exec -i -u 0 -e PGPASSWORD="$OWNER_PW" -e PGOPTIONS="-c client_min_messages=warning" "$APP_C" "$@"; }
kc_exec() { docker exec -i -u 0 -e PGPASSWORD="$KC_PW" -e PGOPTIONS="-c client_min_messages=warning" "$KC_C" "$@"; }
app_psql() { app_exec psql -X -q -At -v ON_ERROR_STOP=1 -h 127.0.0.1 -U cadence "$@"; }
kc_psql() { kc_exec psql -X -q -At -v ON_ERROR_STOP=1 -h 127.0.0.1 -U keycloak "$@"; }
manifest_value() { sed -n "s/^$2|//p" "$1" | head -n 1; }   # <file> <key>

wait_ready() { # <container> <user> <db>
    local i=0
    until docker exec "$1" pg_isready -q -h 127.0.0.1 -U "$2" -d "$3" >/dev/null 2>&1; do
        i=$((i + 1))
        [ "$i" -le 90 ] || return 1
        sleep 1
    done
}

# ---- 1. start the throwaway servers ----------------------------------------------------------------------------------
step "Starting throwaway database containers"
# The backup directory is mounted read-only; data directories are anonymous volumes (removed with the container).
docker run -d --name "$APP_C" --label "$LABEL" --network none \
    -e POSTGRES_USER=cadence -e POSTGRES_DB=postgres -e POSTGRES_PASSWORD="$OWNER_PW" \
    -v "$SRC:/backup:ro" "$TS_IMAGE" >/dev/null || { fail "could not start $TS_IMAGE"; echo; echo "RESTORE DRILL: FAIL"; exit 1; }
docker run -d --name "$KC_C" --label "$LABEL" --network none \
    -e POSTGRES_USER=keycloak -e POSTGRES_DB=keycloak -e POSTGRES_PASSWORD="$KC_PW" \
    -v "$SRC:/backup:ro" "$PG_IMAGE" >/dev/null || { fail "could not start $PG_IMAGE"; echo; echo "RESTORE DRILL: FAIL"; exit 1; }
wait_ready "$APP_C" cadence postgres || { fail "application database container did not become ready"; echo; echo "RESTORE DRILL: FAIL"; exit 1; }
wait_ready "$KC_C" keycloak keycloak || { fail "Keycloak database container did not become ready"; echo; echo "RESTORE DRILL: FAIL"; exit 1; }
pass "throwaway servers are up"

# ---- 2. integrity of the backup files --------------------------------------------------------------------------------
step "Checking the backup files"
if sums=$(docker exec -u 0 -w /backup "$KC_C" sha256sum -c SHA256SUMS 2>&1); then
    pass "SHA256SUMS: all files match"
else
    fail "SHA256SUMS: checksum mismatch (the backup was altered or is corrupt)"
    printf '%s\n' "$sums" | sed 's/^/       /'
fi
docker exec -u 0 "$APP_C" sh -c 'cat /backup/cadence.manifest' >"$WORK/app.manifest"
docker exec -u 0 "$KC_C" sh -c 'cat /backup/keycloak.manifest' >"$WORK/kc.manifest"
BACKUP_TS_VERSION=$(manifest_value "$WORK/app.manifest" timescaledb_version)
[ -n "$BACKUP_TS_VERSION" ] || fail "cadence.manifest has no timescaledb_version line"

# ---- 3. restore the application database (TimescaleDB procedure) -----------------------------------------------------
step "Restoring the application database"
RESTORED_TS_VERSION=$(app_psql -d postgres -c "SELECT default_version FROM pg_available_extensions WHERE name = 'timescaledb'")
if [ "$RESTORED_TS_VERSION" = "$BACKUP_TS_VERSION" ]; then
    pass "TimescaleDB extension version $RESTORED_TS_VERSION equals the version at backup time"
else
    fail "TimescaleDB extension version differs: backup $BACKUP_TS_VERSION, restore image $RESTORED_TS_VERSION (a logical restore needs the same version)"
fi

# Roles are cluster-level and are not part of a database dump: recreate them first. The bootstrap role (cadence) already
# exists in the fresh server, so "already exists" is expected; nothing else is.
app_exec psql -X -q -h 127.0.0.1 -U cadence -d postgres -f /backup/cadence-roles.sql >"$WORK/roles.out" 2>&1
role_errors=$(grep -i 'error' "$WORK/roles.out" | grep -v 'already exists' || true)
if [ -n "$role_errors" ]; then
    fail "roles restore reported errors"
    printf '%s\n' "$role_errors" | sed 's/^/       /'
elif [ "$(app_psql -d postgres -c "SELECT count(*) FROM pg_roles WHERE rolname = 'cadence_app'")" = 1 ]; then
    pass "role cadence_app recreated from cadence-roles.sql (no password in the file)"
else
    fail "role cadence_app does not exist after the roles restore"
fi
# The roles file carries no passwords: set one for the drill, as the real procedure sets APP_DB_PASSWORD again.
app_psql -d postgres -c "ALTER ROLE cadence_app PASSWORD '$APPRUN_PW'" >/dev/null

if app_psql -d postgres -c "CREATE DATABASE cadence" >/dev/null &&
   app_psql -d cadence -c "CREATE EXTENSION IF NOT EXISTS timescaledb" >/dev/null &&
   app_psql -d cadence -c "SELECT timescaledb_pre_restore()" >/dev/null; then
    pass "database created, extension loaded, timescaledb_pre_restore() called"
else
    fail "could not prepare the target database for the restore"
fi

app_exec pg_restore -h 127.0.0.1 -U cadence -d cadence /backup/cadence.dump >"$WORK/restore-app.out" 2>&1
# Known benign: the dump recreates the extension that the target already has.
restore_errors=$(grep -v -e '^pg_restore: error: could not execute query: ERROR:  extension "timescaledb" already exists' \
                         -e '^Command was: CREATE EXTENSION IF NOT EXISTS timescaledb' \
                         -e '^pg_restore: warning: errors ignored on restore: 1$' \
                         -e '^pg_restore: error: could not execute query: ERROR:  must be owner of extension timescaledb' \
                         -e '^Command was: COMMENT ON EXTENSION timescaledb' \
                         -e '^$' "$WORK/restore-app.out" || true)
if [ -n "$restore_errors" ]; then
    fail "pg_restore of the application database reported unexpected messages"
    printf '%s\n' "$restore_errors" | head -n 20 | sed 's/^/       /'
else
    pass "pg_restore of cadence.dump finished without unexpected errors"
fi
if app_psql -d cadence -c "SELECT timescaledb_post_restore()" >/dev/null; then
    pass "timescaledb_post_restore() called"
else
    fail "timescaledb_post_restore() failed"
fi

# ---- 4. restore the Keycloak database --------------------------------------------------------------------------------
step "Restoring the Keycloak database"
if kc_exec pg_restore --exit-on-error -h 127.0.0.1 -U keycloak -d keycloak /backup/keycloak.dump >"$WORK/restore-kc.out" 2>&1; then
    pass "pg_restore of keycloak.dump finished without errors"
else
    fail "pg_restore of keycloak.dump failed"
    head -n 20 "$WORK/restore-kc.out" | sed 's/^/       /'
fi

# ---- 5. compare with the backup-time manifests -----------------------------------------------------------------------
compare() { # <label> <manifest file> <restored output file>
    local label=$1 expected=$2 actual=$3
    grep -v '^info|' "$expected" | LC_ALL=C sort >"$WORK/exp.sorted"
    grep -v '^info|' "$actual" | LC_ALL=C sort >"$WORK/act.sorted"
    if diff "$WORK/exp.sorted" "$WORK/act.sorted" >"$WORK/diff.out"; then
        pass "$label: all $(wc -l <"$WORK/exp.sorted" | tr -d ' ') recorded values equal the restored database"
    else
        fail "$label: restored database differs from the backup manifest (< backup, > restored)"
        sed 's/^/       /' "$WORK/diff.out"
    fi
}

step "Comparing the application database with the backup manifest"
cat "$SQL_DIR/manifest-tables.sql" "$SQL_DIR/manifest-app.sql" | app_psql -d cadence -f - >"$WORK/app.restored" 2>"$WORK/app.restored.err" ||
    { fail "could not query the restored application database"; sed 's/^/       /' "$WORK/app.restored.err"; }
compare "application database" "$WORK/app.manifest" "$WORK/app.restored"

flyway=$(manifest_value "$WORK/app.restored" flyway_version)
flyway_backup=$(manifest_value "$WORK/app.manifest" flyway_version)
if [ -n "$flyway" ] && [ "$flyway" = "$flyway_backup" ]; then
    pass "Flyway history: version $flyway (same as at backup time)"
else
    fail "Flyway history: backup '$flyway_backup', restored '$flyway'"
fi
for t in tenant client appointment audit_log outbox_event; do
    b=$(manifest_value "$WORK/app.manifest" "count|public.$t")
    r=$(manifest_value "$WORK/app.restored" "count|public.$t")
    if [ -z "$b" ]; then
        fail "table $t: not in the backup manifest"
    elif [ "$b" = "$r" ]; then
        pass "table $t: $r rows (backup $b)"
    else
        fail "table $t: backup $b rows, restored ${r:-none}"
    fi
done

ht=$(manifest_value "$WORK/app.restored" hypertables)
chunks=$(manifest_value "$WORK/app.restored" chunks)
outside=$(manifest_value "$WORK/app.restored" chunks_outside_cadence_chunks)
if [ "${ht:-0}" -gt 0 ] 2>/dev/null; then pass "hypertables restored: $ht"; else fail "no hypertables after the restore"; fi
if [ "${chunks:-0}" -gt 0 ] 2>/dev/null; then
    pass "chunks restored: $chunks"
else
    warn "no chunks in the backup (hypertables are empty): chunk placement cannot be proven by this backup"
fi
if [ "${outside:-1}" = 0 ]; then pass "all chunks live in schema cadence_chunks"; else fail "chunks outside cadence_chunks: ${outside:-unknown}"; fi
if [ "$(manifest_value "$WORK/app.restored" job_revoke_chunk_privileges)" = 1 ]; then
    pass "TimescaleDB job cadence_revoke_chunk_privileges (V11) exists"
else
    fail "TimescaleDB job cadence_revoke_chunk_privileges (V11) is missing"
fi
bgw=$(app_psql -d cadence -c "SELECT count(*) FROM timescaledb_information.jobs WHERE proc_name = 'cadence_revoke_chunk_privileges' AND scheduled")
[ "$bgw" = 1 ] && pass "job cadence_revoke_chunk_privileges is scheduled" || fail "job cadence_revoke_chunk_privileges is not scheduled"

step "Checking row-level security for the runtime role"
role_flags=$(app_psql -d cadence -c "SELECT rolsuper OR rolbypassrls OR rolcreaterole FROM pg_roles WHERE rolname = 'cadence_app'")
[ "$role_flags" = f ] && pass "cadence_app: no superuser, BYPASSRLS or CREATEROLE" || fail "cadence_app has elevated attributes after the restore"
[ "$(app_psql -d cadence -c "SELECT has_schema_privilege('cadence_app', 'cadence_chunks', 'USAGE')")" = f ] &&
    pass "cadence_app has no USAGE on schema cadence_chunks" || fail "cadence_app can use schema cadence_chunks"

# As cadence_app over TCP with its password, exactly like the application connects.
app_rls() { docker exec -i -u 0 -e PGPASSWORD="$APPRUN_PW" "$APP_C" psql -X -q -At -v ON_ERROR_STOP=1 -h 127.0.0.1 -U cadence_app -d cadence "$@"; }
tenant_id=$(app_psql -d cadence -c "SELECT tenant_id FROM client ORDER BY tenant_id LIMIT 1")
if [ -z "$tenant_id" ]; then
    warn "no client rows in the backup: isolation cannot be demonstrated by this backup"
else
    for tbl in client appointment audit_log; do
        total=$(app_psql -d cadence -c "SELECT count(*) FROM $tbl")
        mine=$(app_psql -d cadence -c "SELECT count(*) FROM $tbl WHERE tenant_id = '$tenant_id'")
        if [ "$total" = 0 ]; then
            warn "$tbl: empty in the backup, RLS not demonstrable on it"
            continue
        fi
        none=$(app_rls -c "SELECT count(*) FROM $tbl" 2>"$WORK/rls.err") || { fail "$tbl: cadence_app cannot query it"; sed 's/^/       /' "$WORK/rls.err"; continue; }
        scoped=$(app_rls -c "BEGIN; SELECT set_config('app.current_tenant_id', '$tenant_id', true); SELECT count(*) FROM $tbl; COMMIT;" 2>"$WORK/rls.err" | sed -n '2p')
        if [ "$none" = 0 ] && [ "$scoped" = "$mine" ] && [ "$mine" -gt 0 ]; then
            pass "$tbl: cadence_app sees 0 of $total rows without tenant context, $scoped with the context of one tenant"
        else
            fail "$tbl: RLS broken after the restore (no context: $none, with context: ${scoped:-?}, expected 0 and $mine; total $total)"
        fi
    done
fi

step "Comparing the Keycloak database with the backup manifest"
cat "$SQL_DIR/manifest-tables.sql" | kc_psql -d keycloak -f - >"$WORK/kc.restored" 2>"$WORK/kc.restored.err" ||
    { fail "could not query the restored Keycloak database"; sed 's/^/       /' "$WORK/kc.restored.err"; }
compare "Keycloak database" "$WORK/kc.manifest" "$WORK/kc.restored"
realms=$(manifest_value "$WORK/kc.restored" "count|public.realm")
[ "${realms:-0}" -ge 1 ] 2>/dev/null && pass "Keycloak realms restored: $realms" || fail "no Keycloak realm after the restore"

# ---- result ----------------------------------------------------------------------------------------------------------
printf '\n'
if [ "$FAILS" -eq 0 ]; then
    printf 'RESTORE DRILL: PASS (%s, %s warning(s))\n' "$(basename "$SRC")" "$WARNS"
    exit 0
fi
printf 'RESTORE DRILL: FAIL (%s, %s check(s) failed, %s warning(s))\n' "$(basename "$SRC")" "$FAILS" "$WARNS"
exit 1
