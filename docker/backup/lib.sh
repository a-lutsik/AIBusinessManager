# Shared helpers of the Cadence backup service (POSIX sh, runs on busybox in the timescale/timescaledb image).

log() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

# "90" / "90s" -> 90, "30m" -> 1800, "6h" -> 21600, "1d" -> 86400. Prints seconds; returns 1 on garbage or < 60 s.
parse_interval() {
    case "$1" in
        *[!0-9smhd]* | '' ) return 1 ;;
    esac
    num=${1%[smhd]}
    unit=${1#"$num"}
    case "$num" in
        '' | *[!0-9]*) return 1 ;;
    esac
    case "$unit" in
        '' | s) secs=$num ;;
        m) secs=$((num * 60)) ;;
        h) secs=$((num * 3600)) ;;
        d) secs=$((num * 86400)) ;;
        *) return 1 ;;
    esac
    [ "$secs" -ge 60 ] || return 1
    printf '%s\n' "$secs"
}
