#!/bin/sh
# Runs ./gradlew with the given arguments and repeats the whole run, at most three times in all, when it failed because
# Maven Central answered 429 Too Many Requests. Gradle 8.14 retries only 5xx answers, so a rate-limited download fails
# the build at once; a pause and a second run then resolve from what the first run already put in the Gradle cache.
# Any other failure (a compile error, a red test) is reported after the first run, without repeating it.
#
# POSIX sh on purpose: the Dockerfile runs it too. Usage (from backend/): ci/gradle-retry.sh <gradle arguments>
set -u
log="${TMPDIR:-/tmp}/gradle-retry.$$.log"
attempt=1
while :; do
    # Streamed as it runs; the exit status travels through a file because sh has no pipefail.
    { ./gradlew "$@" 2>&1; echo $? >"$log.status"; } | tee "$log"
    status=$(cat "$log.status")
    if [ "$status" -eq 0 ]; then
        rm -f "$log" "$log.status"
        exit 0
    fi
    if [ "$attempt" -ge 3 ] || ! grep -q 'Received status code 429' "$log"; then
        rm -f "$log" "$log.status"
        exit "$status"
    fi
    pause=$((attempt * 45))
    echo "gradle-retry: Maven Central answered 429 Too Many Requests; run $attempt of 3 failed, retrying in ${pause} s"
    sleep "$pause"
    attempt=$((attempt + 1))
done
