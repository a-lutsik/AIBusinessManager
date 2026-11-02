#!/usr/bin/env bash
# Route check of the web image on the real nginx (TD-57): builds docker/web/Dockerfile, runs the container the way
# docker-compose.prod.yml does (read-only, no capabilities, non-root) and asks it for every kind of URL over HTTP.
#
#   scripts/web-host-check.sh [--no-build] [--keep-image]
#
# Everything it creates is named cadence-web-check; the container always goes, the image unless --keep-image.
# Needs docker and curl.
set -euo pipefail
# No grep -q at the end of a pipe: it closes the pipe on the first match, and under pipefail that SIGPIPE reads as no match.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE=cadence-web-check:local
CONTAINER="cadence-web-check-$$"
API=https://api.example.test
ISSUER=https://auth.example.test/realms/cadence
BUILD=1
KEEP_IMAGE=0
FAILED=0

for arg in "$@"; do
    case "$arg" in
        --no-build) BUILD=0 ;;
        --keep-image) KEEP_IMAGE=1 ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

WORK="$(mktemp -d)"
cleanup() {
    docker rm -f -v "$CONTAINER" >/dev/null 2>&1 || true
    if [ "$KEEP_IMAGE" = 0 ]; then docker rmi "$IMAGE" >/dev/null 2>&1 || true; fi
    rm -rf "$WORK"
}
trap cleanup EXIT

fail() { echo "FAIL  $*"; FAILED=1; }
pass() { echo "ok    $*"; }

digest() {
    if command -v sha256sum >/dev/null; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi
}

build() {
    docker build --quiet -f "$ROOT/docker/web/Dockerfile" -t "$IMAGE" "$ROOT" "$@"
}

if [ "$BUILD" = 1 ]; then
    for missing in EXPO_PUBLIC_API_URL EXPO_PUBLIC_OIDC_ISSUER EXPO_PUBLIC_AUTH_MODE; do
        args=(--build-arg "EXPO_PUBLIC_API_URL=$API" --build-arg "EXPO_PUBLIC_OIDC_ISSUER=$ISSUER" --build-arg EXPO_PUBLIC_AUTH_MODE=oidc
              --build-arg "$missing=")
        if docker build --progress=plain --target build -f "$ROOT/docker/web/Dockerfile" -t cadence-web-check:refused "$ROOT" "${args[@]}" \
                >"$WORK/refused.log" 2>&1; then
            docker rmi cadence-web-check:refused >/dev/null 2>&1 || true
            fail "the build accepted an empty $missing"
        elif grep -q "build argument $missing is empty" "$WORK/refused.log"; then
            pass "an empty $missing stops the build and names it"
        else
            tail -5 "$WORK/refused.log"
            fail "the build failed for another reason than an empty $missing"
        fi
    done
    build --build-arg "EXPO_PUBLIC_API_URL=$API" --build-arg "EXPO_PUBLIC_OIDC_ISSUER=$ISSUER" --build-arg EXPO_PUBLIC_AUTH_MODE=oidc >/dev/null
fi

docker run -d --rm --name "$CONTAINER" --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
    -p 127.0.0.1::8082 "$IMAGE" >/dev/null
BASE="http://$(docker port "$CONTAINER" 8082/tcp | head -1)"
for _ in $(seq 1 50); do
    curl -fsS -o /dev/null "$BASE/healthz" 2>/dev/null && break
    sleep 0.2
done

html_root=/usr/share/nginx/html
file_digest() { docker exec "$CONTAINER" cat "$html_root/$1" | digest; }

# page <url> <status> <file served>
page() {
    local url="$1" want="$2" file="$3" status got
    status="$(curl -sS --path-as-is -o "$WORK/body" -w '%{http_code}' "$BASE$url")"
    got="$(digest <"$WORK/body")"
    if [ "$status" != "$want" ]; then
        fail "$url: status $status, wanted $want"
    elif [ "$got" != "$(file_digest "$file")" ]; then
        fail "$url: the body is not $file"
    else
        pass "$url -> $status $file"
    fi
}

# outside <url>: the path leaves the web root or names something that is no URL of the app.
outside() {
    local url="$1" status
    status="$(curl -sS --path-as-is -o "$WORK/body" -w '%{http_code}' "$BASE$url")"
    if [ "$status" = 200 ] || grep -q 'root:' "$WORK/body"; then
        fail "$url: status $status, the body must not be a file from outside"
    else
        pass "$url -> $status, nothing from outside"
    fi
}

# header <url> <header line regex>
header() {
    if curl -sS --path-as-is -o /dev/null -D - "$BASE$1" | tr -d '\r' | grep -Ei "$2" >/dev/null; then
        pass "$1 has $2"
    else
        fail "$1 has no $2"
    fi
}

page / 200 index.html
page /dashboard 200 dashboard.html
page /calendar 200 calendar.html
page /calendar/ 200 calendar.html
page /clients 200 clients.html
page /specialists 200 specialists.html
page '/auth?code=abc&state=def' 200 auth.html
page /book/lumen-studio 200 'book/[slug].html'
page /book/x 200 'book/[slug].html'
page /book/x/ 200 'book/[slug].html'
page '/b/x?token=y' 200 'b/[id].html'
page /clients/x 200 'clients/[id].html'
page /specialists/x 200 'specialists/[id].html'
page /no-such-page 404 +not-found.html
page /book 404 +not-found.html
page /book/x/y 404 +not-found.html
page /clients/x/y 404 +not-found.html
page /b/ 404 +not-found.html
page /no-such-page/ 404 +not-found.html
page /_expo/static/ 404 +not-found.html
page /+not-found.html 404 +not-found.html
page '/(console)/calendar' 404 +not-found.html
page /_sitemap 404 +not-found.html
page /_expo/static/js/web/no-such-file.js 404 +not-found.html

outside /../../etc/passwd
outside /..%2f..%2f..%2fetc%2fpasswd
outside /%2e%2e/%2e%2e/%2e%2e/etc/passwd
outside /book/..%2f..%2f..%2f..%2fetc%2fpasswd
outside /assets/../../../../etc/passwd
outside /_expo/static/../../../../../etc/passwd
outside /etc/nginx/conf.d/default.conf
outside /.git/config

bundle="$(docker exec "$CONTAINER" sh -c "ls $html_root/_expo/static/js/web/ | head -1")"
for url in /book/x /no-such-page "/_expo/static/js/web/$bundle"; do
    header "$url" '^x-content-type-options: nosniff'
    header "$url" '^x-frame-options: DENY'
    header "$url" '^referrer-policy: no-referrer'
    header "$url" "^content-security-policy: .*connect-src 'self' https://api\.example\.test https://auth\.example\.test;"
done
header /book/x '^cache-control: no-cache'
header /no-such-page '^cache-control: no-cache'
header "/_expo/static/js/web/$bundle" '^cache-control: public, max-age=31536000, immutable'
if curl -sS -o /dev/null -D - "$BASE/" | tr -d '\r' | grep -Ei '^server: .*[0-9]' >/dev/null; then fail "the Server header names a version"; else pass "the Server header names no version"; fi

uid="$(docker exec "$CONTAINER" id -u)"
if [ "$uid" = 0 ]; then fail "the container runs as root"; else pass "the container runs as uid $uid"; fi
if docker exec "$CONTAINER" sh -c "grep -rlE 'localhost:(8080|8081)' $html_root/_expo/static/js" | grep . >/dev/null; then
    fail "the bundle names localhost"
else
    pass "the bundle does not name localhost"
fi
if docker exec "$CONTAINER" sh -c "grep -rl 'api.example.test' $html_root/_expo/static/js" | grep . >/dev/null; then
    pass "the bundle names the API of the build"
else
    fail "the bundle does not name the API of the build"
fi
access_log="$(docker logs "$CONTAINER" 2>&1)"
if grep -q '/b/x' <<<"$access_log"; then pass "the access log holds the request for /b/x"; else fail "the access log holds no request for /b/x"; fi
if grep -q 'token=' <<<"$access_log"; then fail "the access log holds a token"; else pass "the access log holds no token"; fi

if [ "$FAILED" != 0 ]; then
    echo "web-host-check: FAILED"
    exit 1
fi
echo "web-host-check: PASS"
