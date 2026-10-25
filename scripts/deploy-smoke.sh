#!/usr/bin/env bash
# Cadence deploy smoke: checks a running deployment from the outside, the way a browser and an attacker would see it.
# Run it after the first deploy (and after changes to the reverse proxy, KC_HOSTNAME or CADENCE_WEB_ORIGIN), from any
# machine that can reach the PUBLIC URLs; add --check-ports when it runs on the server itself.
#
#   scripts/deploy-smoke.sh --app-url https://api.example.com --kc-url https://auth.example.com \
#                           --web-origin https://app.example.com [--check-ports] [--strict]
#
# Inputs (flag or environment variable):
#   --app-url URL       APP_URL        public base URL of the API (no trailing slash), what the console calls
#   --kc-url URL        KC_URL         public URL of Keycloak = KC_HOSTNAME from .env.prod
#   --web-origin ORIGIN WEB_ORIGIN     origin of the web console = CADENCE_WEB_ORIGIN from .env.prod
#   --token-file FILE   ACCESS_TOKEN   optional access token of a real user (OWNER) of realm cadence, for the authenticated
#                                      check. Prefer the environment variable or a file: a flag value shows up in `ps`.
#                                      Get one by signing in to the console and copying the bearer token from the browser's
#                                      network tab (it is valid for a few minutes).
#   --realm NAME        KC_REALM       realm name (default: cadence)
#   --check-ports       CHECK_PORTS=1  also inspect listening sockets of THIS host (ss, or lsof on macOS): no 5432/5433
#                                      on a public address, 8080/8081 only on loopback
#   --strict            STRICT=1       treat WARN as FAIL (use this for the real production run)
#
# The access token is never put on a command line (it would be visible in `ps`): curl reads it from a 0600 header file
# in the script's private temp directory, removed on exit.
#
# Output: one [PASS] / [FAIL] / [WARN] line per check, then "DEPLOY SMOKE: PASS|FAIL". Exit status 0 = no failed check
# (and, with --strict, no warning), 1 = at least one failed check, 2 = usage error.
# Needs bash, curl and (for the token issuer check) openssl; nothing is changed on the target. The only requests
# with side effects are two rejected login attempts of a user that does not exist.

set -u -o pipefail

APP_URL=${APP_URL:-}
KC_URL=${KC_URL:-}
WEB_ORIGIN=${WEB_ORIGIN:-}
KC_REALM=${KC_REALM:-cadence}
ACCESS_TOKEN=${ACCESS_TOKEN:-}
TOKEN_FILE=""
CHECK_PORTS=${CHECK_PORTS:-0}
STRICT=${STRICT:-0}

usage() {
    sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --app-url) APP_URL=${2:-}; shift 2 ;;
        --kc-url) KC_URL=${2:-}; shift 2 ;;
        --web-origin) WEB_ORIGIN=${2:-}; shift 2 ;;
        --realm) KC_REALM=${2:-}; shift 2 ;;
        --token-file) TOKEN_FILE=${2:-}; shift 2 ;;
        --check-ports) CHECK_PORTS=1; shift ;;
        --strict) STRICT=1; shift ;;
        -h | --help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done
APP_URL=${APP_URL%/}
KC_URL=${KC_URL%/}
WEB_ORIGIN=${WEB_ORIGIN%/}
[ -n "$APP_URL" ] && [ -n "$KC_URL" ] && [ -n "$WEB_ORIGIN" ] || { echo "--app-url, --kc-url and --web-origin are required" >&2; usage; }
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 2; }

ISSUER=$KC_URL/realms/$KC_REALM
FAILS=0
WARNS=0
pass() { printf '[PASS] %s\n' "$*"; }
fail() { printf '[FAIL] %s\n' "$*"; FAILS=$((FAILS + 1)); }
warn() { printf '[WARN] %s\n' "$*"; WARNS=$((WARNS + 1)); }
step() { printf '\n== %s\n' "$*"; }

WORK=$(mktemp -d "${TMPDIR:-/tmp}/cadence-smoke.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
chmod 700 "$WORK"

# An unreadable --token-file is a failed check, never a silent downgrade to "no token".
TOKEN_FILE_ERROR=""
if [ -n "$TOKEN_FILE" ]; then
    if [ -r "$TOKEN_FILE" ] && [ -f "$TOKEN_FILE" ]; then
        ACCESS_TOKEN=$(tr -d '[:space:]' <"$TOKEN_FILE") || TOKEN_FILE_ERROR="cannot read --token-file $TOKEN_FILE"
        [ -n "$TOKEN_FILE_ERROR" ] || [ -n "$ACCESS_TOKEN" ] || TOKEN_FILE_ERROR="--token-file $TOKEN_FILE is empty"
    else
        TOKEN_FILE_ERROR="--token-file $TOKEN_FILE is missing or not readable"
    fi
fi

# request <method> <url> [curl args...]  -> sets CODE, writes $WORK/headers and $WORK/body. Redirects are NOT followed.
CODE=000
request() {
    local method=$1 url=$2
    shift 2
    : >"$WORK/headers"; : >"$WORK/body"
    CODE=$(curl -sS -m 20 -X "$method" -o "$WORK/body" -D "$WORK/headers" -w '%{http_code}' "$@" "$url" 2>"$WORK/curl.err") || CODE=000
}
header() { grep -i "^$1:" "$WORK/headers" | head -n 1 | sed 's/^[^:]*: *//' | tr -d '\r'; }
json_str() { tr -d '\n' <"$WORK/body" | sed -n "s/.*\"$1\" *: *\"\\([^\"]*\\)\".*/\\1/p" | head -n 1; }
curl_failed() { [ "$CODE" = 000 ] && { fail "$1: no HTTP answer ($(tr -d '\n' <"$WORK/curl.err"))"; return 0; }; return 1; }
is_loopback_url() { case "$1" in http://localhost* | http://127.0.0.1* | http://\[::1\]* | https://localhost* | https://127.0.0.1*) return 0 ;; esac; return 1; }

printf 'Cadence deploy smoke\n'
printf '       app:        %s\n       keycloak:   %s (realm %s)\n       web origin: %s\n' "$APP_URL" "$KC_URL" "$KC_REALM" "$WEB_ORIGIN"

# ---- transport -------------------------------------------------------------------------------------------------------
step "Public URLs"
for pair in "app:$APP_URL" "keycloak:$KC_URL"; do
    name=${pair%%:*}; url=${pair#*:}
    case "$url" in
        https://*) pass "$name URL uses https" ;;
        *)
            if is_loopback_url "$url"; then
                warn "$name URL is plain http on loopback: fine for a local run, not for a deployment"
            else
                fail "$name URL $url is not https (Keycloak's sslRequired=external rejects sign-in over plain http from outside)"
            fi
            ;;
    esac
done

# ---- application -----------------------------------------------------------------------------------------------------
step "Application"
request GET "$APP_URL/actuator/health"
if ! curl_failed "GET /actuator/health"; then
    if [ "$CODE" = 200 ] && grep -q '"status" *: *"UP"' "$WORK/body"; then
        pass "GET /actuator/health -> 200 UP"
    else
        fail "GET /actuator/health -> $CODE (expected 200 with status UP)"
    fi
fi
for path in /actuator/env /actuator/metrics /actuator/beans; do
    request GET "$APP_URL$path"
    case "$CODE" in
        200) fail "GET $path -> 200: actuator endpoints beyond health must not be exposed" ;;
        000) curl_failed "GET $path" ;;
        *) pass "GET $path -> $CODE (not exposed)" ;;
    esac
done
request GET "$APP_URL/api/app/me"
if ! curl_failed "GET /api/app/me"; then
    if [ "$CODE" = 401 ]; then pass "GET /api/app/me without token -> 401"; else fail "GET /api/app/me without token -> $CODE (expected 401)"; fi
fi
request GET "$APP_URL/api/app/me" -H "Authorization: Bearer not-a-token"
if ! curl_failed "GET /api/app/me with a garbage token"; then
    if [ "$CODE" = 401 ]; then pass "GET /api/app/me with a garbage token -> 401"; else fail "GET /api/app/me with a garbage token -> $CODE (expected 401)"; fi
fi

# ---- CORS ------------------------------------------------------------------------------------------------------------
step "CORS (browser access from the console)"
request OPTIONS "$APP_URL/api/app/me" -H "Origin: $WEB_ORIGIN" -H "Access-Control-Request-Method: GET" \
    -H "Access-Control-Request-Headers: authorization"
if ! curl_failed "preflight from $WEB_ORIGIN"; then
    allow=$(header access-control-allow-origin)
    if [ "$CODE" = 200 ] && [ "$allow" = "$WEB_ORIGIN" ]; then
        pass "preflight from $WEB_ORIGIN -> 200, Access-Control-Allow-Origin: $allow"
    else
        fail "preflight from $WEB_ORIGIN -> $CODE, Access-Control-Allow-Origin '${allow:-none}' (expected 200 and the origin itself; check CADENCE_WEB_ORIGIN = APP_CORS_ALLOWED_ORIGINS)"
    fi
fi
request OPTIONS "$APP_URL/api/app/me" -H "Origin: https://not-the-console.invalid" -H "Access-Control-Request-Method: GET" \
    -H "Access-Control-Request-Headers: authorization"
if ! curl_failed "preflight from a foreign origin"; then
    allow=$(header access-control-allow-origin)
    if [ "$CODE" = 403 ] && [ -z "$allow" ]; then
        pass "preflight from a foreign origin -> 403"
    else
        fail "preflight from a foreign origin -> $CODE, Access-Control-Allow-Origin '${allow:-none}' (expected 403 without the header)"
    fi
fi

# ---- Keycloak --------------------------------------------------------------------------------------------------------
step "Keycloak"
request GET "$ISSUER/.well-known/openid-configuration"
if ! curl_failed "GET discovery document"; then
    if [ "$CODE" != 200 ]; then
        fail "GET $ISSUER/.well-known/openid-configuration -> $CODE (expected 200)"
    else
        issuer=$(json_str issuer)
        if [ "$issuer" = "$ISSUER" ]; then
            pass "discovery issuer equals $ISSUER (= APP_SECURITY_ISSUER_URI)"
        else
            fail "discovery issuer is '$issuer', expected '$ISSUER' (KC_HOSTNAME / X-Forwarded-Host / X-Forwarded-Proto of the proxy?)"
        fi
        bad=0
        for key in authorization_endpoint token_endpoint jwks_uri end_session_endpoint; do
            value=$(json_str "$key")
            case "$value" in "$KC_URL"/*) ;; *) fail "discovery $key is '$value', it must start with $KC_URL/"; bad=1 ;; esac
        done
        [ "$bad" = 0 ] && pass "discovery endpoints (authorization, token, jwks, end_session) all start with $KC_URL/"
    fi
fi
for client in cadence-app admin-cli; do
    request POST "$ISSUER/protocol/openid-connect/token" -H "Content-Type: application/x-www-form-urlencoded" \
        --data-urlencode "grant_type=password" --data-urlencode "client_id=$client" \
        --data-urlencode "username=smoke-nonexistent-user" --data-urlencode "password=smoke-nonexistent-password"
    curl_failed "password grant for $client" && continue
    err=$(json_str error)
    if [ "$err" = unauthorized_client ]; then
        pass "password grant for $client -> unauthorized_client ($CODE)"
    else
        fail "password grant for $client -> $CODE error '${err:-none}' (expected unauthorized_client: direct access grants must be off)"
    fi
done
step "Keycloak admin surface must not be public"
for path in /realms/master/.well-known/openid-configuration /realms/master/protocol/openid-connect/token /admin/master/console/ /admin/; do
    request GET "$KC_URL$path"
    case "$CODE" in
        403 | 404) pass "GET $path -> $CODE (blocked)" ;;
        000) curl_failed "GET $path" ;;
        *) warn "GET $path -> $CODE: reachable through the public URL; the proxy must serve only /realms/$KC_REALM/ and /resources/ (README, Reverse proxy). Expected only on a local run without a proxy." ;;
    esac
done

# ---- authenticated check ---------------------------------------------------------------------------------------------
step "Authenticated request"
if [ -n "$TOKEN_FILE_ERROR" ]; then
    fail "$TOKEN_FILE_ERROR: the authenticated check could not run"
elif [ -z "$ACCESS_TOKEN" ]; then
    warn "no ACCESS_TOKEN: the check 'valid token of the public issuer -> /api/app/me 200' was skipped (TD-21 requires it once)"
else
    if command -v openssl >/dev/null 2>&1; then
        payload=$(printf '%s' "$ACCESS_TOKEN" | cut -d. -f2 | tr '_-' '/+')
        while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="$payload="; done
        claims=$(printf '%s' "$payload" | openssl base64 -d -A 2>/dev/null || true)
        iss=$(printf '%s' "$claims" | sed -n 's/.*"iss" *: *"\([^"]*\)".*/\1/p')
        if [ "$iss" = "$ISSUER" ]; then pass "token issuer equals $ISSUER"; else fail "token issuer is '${iss:-unreadable}', expected '$ISSUER'"; fi
    elif [ "$STRICT" = 1 ]; then
        fail "openssl is not installed: the token issuer check could not run (install openssl, or run without --strict)"
    else
        warn "openssl is not installed: the token issuer check was skipped"
    fi
    # Header file instead of `-H "Authorization: ..."`: a command-line argument is visible to every user in `ps`.
    ( umask 077; printf 'Authorization: Bearer %s\n' "$ACCESS_TOKEN" >"$WORK/auth.header" ) \
        || fail "cannot write the token header file in $WORK"
    request GET "$APP_URL/api/app/me" -H "@$WORK/auth.header"
    if ! curl_failed "GET /api/app/me with a token"; then
        if [ "$CODE" = 200 ]; then
            pass "GET /api/app/me with a token of the public issuer -> 200"
        else
            fail "GET /api/app/me with a token -> $CODE: $(head -c 200 "$WORK/body" | tr -d '\n') (401 = issuer/keys mismatch or expired token, 403 = missing tenant_id claim or role)"
        fi
    fi
fi

# ---- listening sockets of this host ----------------------------------------------------------------------------------
if [ "$CHECK_PORTS" = 1 ]; then
    step "Listening sockets on this host"
    if command -v ss >/dev/null 2>&1; then
        listeners=$(ss -ltnH 2>/dev/null | awk '{print $4}')
    elif command -v lsof >/dev/null 2>&1; then
        listeners=$(lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | awk 'NR > 1 {print $9}' | sort -u)
    else
        listeners=""
        warn "neither ss nor lsof is available: listening sockets not checked"
    fi
    if [ -n "$listeners" ]; then
        port_addrs() { printf '%s\n' "$listeners" | grep -E ":$1\$" | sed -E "s/:$1\$//" | sort -u; }
        is_loop() { case "$1" in 127.* | "[::1]" | "::1") return 0 ;; esac; return 1; }
        for port in 5432 5433; do
            addrs=$(port_addrs "$port")
            if [ -z "$addrs" ]; then
                pass "port $port is not listening on this host"
            else
                public=0
                for a in $addrs; do is_loop "$a" || public=1; done
                if [ "$public" = 1 ]; then fail "port $port listens on a non-loopback address ($(echo $addrs)): the database must not be reachable from outside"
                else warn "port $port listens on loopback only ($(echo $addrs)): not part of the prod stack, check what it is"; fi
            fi
        done
        for port in 8080 8081; do
            addrs=$(port_addrs "$port")
            if [ -z "$addrs" ]; then
                warn "port $port was not seen listening (docker may publish it without a userland proxy; confirm with 'docker compose ps')"
            else
                public=0
                for a in $addrs; do is_loop "$a" || public=1; done
                if [ "$public" = 1 ]; then fail "port $port listens on $(echo $addrs): it must be bound to 127.0.0.1 only (the proxy is the only public entry)"
                else pass "port $port listens on loopback only ($(echo $addrs))"; fi
            fi
        done
    fi
fi

# ---- result ----------------------------------------------------------------------------------------------------------
printf '\n'
if [ "$FAILS" -eq 0 ] && { [ "$STRICT" != 1 ] || [ "$WARNS" -eq 0 ]; }; then
    printf 'DEPLOY SMOKE: PASS (%s warning(s))\n' "$WARNS"
    exit 0
fi
if [ "$FAILS" -eq 0 ]; then
    printf 'DEPLOY SMOKE: FAIL (--strict: %s warning(s) count as failures)\n' "$WARNS"
else
    printf 'DEPLOY SMOKE: FAIL (%s check(s) failed, %s warning(s))\n' "$FAILS" "$WARNS"
fi
exit 1
