#!/bin/sh
# Healthy while (a) the newest complete backup directory is not older than two intervals (+15 min) and (b) no backup
# attempt failed after it. (a) is derived from the directory NAME (UTC timestamp), so it needs no marker file: on a full
# disk the failure marker cannot be written, but the missing new backup still turns the service unhealthy.
. /opt/backup/lib.sh
root=${BACKUP_ROOT:-/backups}
interval=$(parse_interval "${BACKUP_INTERVAL:-6h}") || exit 1

newest=$(ls -1d "$root"/2*Z 2>/dev/null | sort | tail -n 1)
if [ -z "$newest" ]; then
    echo "no complete backup yet" >&2
    exit 1
fi
if [ ! -s "$newest/SHA256SUMS" ]; then
    echo "newest backup $newest has no SHA256SUMS" >&2
    exit 1
fi
stamp=${newest##*/}
iso=$(printf '%s' "$stamp" | sed -E 's/^([0-9]{4})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})Z$/\1-\2-\3 \4:\5:\6/')
made=$(date -u -d "$iso" +%s 2>/dev/null)
case "$made" in '' | *[!0-9]*) echo "cannot read the timestamp of $newest" >&2; exit 1 ;; esac

age=$(( $(date +%s) - made ))
if [ "$age" -gt $(( 2 * interval + 900 )) ]; then
    echo "newest backup $stamp is $age s old (limit $(( 2 * interval + 900 )) s)" >&2
    exit 1
fi
if [ -f "$root/.last-failure" ]; then
    failed=$(cat "$root/.last-failure" 2>/dev/null)
    case "$failed" in '' | *[!0-9]*) ;; *)
        if [ "$failed" -gt "$made" ]; then
            echo "a backup attempt failed after $stamp" >&2
            exit 1
        fi ;;
    esac
fi
exit 0
