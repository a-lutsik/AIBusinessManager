# Cadence

Modular monolith for solo specialists and micro-teams. Stack: **Java 25 / Spring Boot 4.1**, **jOOQ + Flyway**, **TimescaleDB**, **Keycloak**, **Expo Router** (RN Web).

Demo tenant: **Lumen Studio** (Tbilisi, `Asia/Tbilisi`, GEL). Masters: Nino Beridze, Anna Novakova, Elena Kravets. Second seed tenant: **Aura Atelier** (Prague, CZK).

## Layout

| Path | Role |
|------|------|
| `waitlist/` | Marketing landing + waitlist form (Phase 0/6) |
| `backend/` | Gradle multi-module modular monolith |
| `frontend/` | Expo Router + React Native Web console and public booking |
| `docker-compose.yml` | TimescaleDB + Keycloak |
| `docs/PRODUCT_PLAN.md` | Canonical product & implementation plan |

### Backend modules

`platform` · `catalog` · `booking-engine` · `crm` · `retention` · `growth` · `ai` · `notification` · `app`

## Prerequisites

- JDK **25** (compile toolchain). Gradle itself can run on JDK 21+ (this repo’s wrapper is typically launched with Corretto 21).
- Node 20+
- Docker / Docker Compose (needed for TimescaleDB, Keycloak, and Testcontainers ITs)

If Docker Desktop is stopped on macOS:

```bash
open -a Docker
```

## Run infrastructure

```bash
docker compose up -d
```

- TimescaleDB: `localhost:5433` (db/user/pass: `cadence` / `cadence` / `cadence`) — host port **5433** so it does not clash with a local Postgres on 5432.
  The backend uses two roles on that database, see [Database roles and RLS](#database-roles-and-rls).
- Keycloak: http://localhost:8081 (admin/admin), realm `cadence`
  - Owner: `owner@lumen.studio` / `owner`
  - Master (Nino): `nino@lumen.studio` / `nino` (JWT `specialist_id` mapped)

## Run backend

```bash
cd backend
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/corretto-21.0.7/Contents/Home"   # Gradle JVM
./gradlew :modules:app:bootRun
```

- Public API: http://localhost:8080/api/public/health
- Public booking JSON: http://localhost:8080/api/public/tenants/lumen-studio
- App API: http://localhost:8080/api/app/health (requires a Keycloak JWT, see below)
- OpenAPI: http://localhost:8080/openapi.yaml

### Authentication

`/api/app/**` is an OAuth2 resource server by default: every request needs a valid JWT from Keycloak
(`APP_SECURITY_ISSUER_URI`, default `http://localhost:8081/realms/cadence`). The issuer is resolved lazily, so the
backend boots even when Keycloak is not running; requests with a token fail until it is up.

- Tenant comes only from the `tenant_id` claim, role from the `roles` claim (`OWNER` wins over `MASTER`), the specialist
  of a master from `specialist_id` (or the Keycloak user mapping). `X-Tenant-Id`, `X-Actor-*` and `X-Specialist-Id`
  headers are ignored.
- No token: 401 `UNAUTHENTICATED`. Token without a valid `tenant_id`: 403 `TENANT_CLAIM_MISSING` (unknown tenant:
  `TENANT_UNKNOWN`). Token without `OWNER`/`MASTER`: 403 `ROLE_MISSING`. Master without a specialist:
  403 `SPECIALIST_UNMAPPED`. There is no fallback to OWNER.
- `/api/public/**` is anonymous; its tenant comes only from the slug in the path.

Header mode for local work without Keycloak (never use it on a shared or production environment):

```bash
SPRING_PROFILES_ACTIVE=dev-headers ./gradlew :modules:app:bootRun   # same as --spring.profiles.active=dev-headers
```

The `dev-headers` profile turns OAuth2 off and trusts `X-Tenant-Id` (required; Lumen UUID
`00000000-0000-4000-8000-000000000001`), optional `X-Actor-Role` (`OWNER` by default, or `MASTER` together with
`X-Specialist-Id`) and `X-Actor-Id`. Invalid values answer 400. Startup fails if neither mode is enabled or both are.

### Public API rate limits

`/api/public/**` is anonymous, so it is throttled in memory (`app.rate-limit.*`; defaults are the numbers of the
product plan, section 11). Exceeding a limit answers `429` `application/problem+json` with `code=RATE_LIMITED`, a
human-readable `detail` and a `Retry-After` header (seconds).

| Limit | Key | Default | Property |
|-------|-----|---------|----------|
| Slot calculation `GET /api/public/tenants/{slug}/slots` | client IP | 5 per minute | `slots-per-minute-per-ip` |
| Booking attempts `POST .../bookings` | normalized E.164 phone (tenant country, per tenant) | 3 per day | `bookings-per-day-per-phone` |
| Booking attempts | tenant slug | 20 per hour | `bookings-per-hour-per-tenant` |
| Booking attempts | client IP | 20 per hour | `bookings-per-hour-per-ip` |

- An attempt counts whether or not the booking later succeeds; a refused request consumes nothing. An attempt whose
  phone cannot be parsed is not counted per phone or per tenant (it falls through to the normal `INVALID_PHONE`
  validation); the per-IP booking limit exists precisely so that such attempts are not unlimited. Raising any
  property above the plan is a conscious choice, e.g. `APP_RATE_LIMIT_SLOTS_PER_MINUTE_PER_IP=10`.
- **Client IP** is the TCP peer address. `X-Forwarded-For` is ignored unless the peer is listed in
  `app.rate-limit.trusted-proxies` (comma-separated CIDRs or addresses, default empty; env
  `APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1/32,10.0.0.0/8`). For a trusted peer the header is walked from the right and
  the first address that is not a trusted proxy is the client; a malformed entry on the way falls back to the peer.
  Behind a reverse proxy (nginx, Caddy, a load balancer) you MUST set `trusted-proxies` to the proxy's address, or all
  clients share the proxy's IP and its limit; never list address ranges that clients can reach directly.
- **Audit:** every rejection writes `public.rate_limited` (actor `PUBLIC`, entity `tenant`, details: which limit, masked
  IP with the last IPv4 octet / IPv6 suffix zeroed, masked phone `+995*******56`; never raw values), at most once per
  minute per exceeded counter. An unknown slug has no tenant scope and is only logged. An audit failure never changes
  the 429.
- **State is in memory of one instance** (single-VPS deployment): it is lost on restart, and several instances behind a
  balancer would each apply the full limit, i.e. multiply it. A shared store (Redis or the database) is needed before
  scaling out. At most `max-tracked-keys` counters (default 100000) are kept; expired ones are swept every minute and,
  when the bound is reached, requests that need a new counter get `429` until old counters expire.

### Database roles and RLS

| Role | Used by | Rights |
|------|---------|--------|
| `cadence` (`FLYWAY_DB_USER` / `FLYWAY_DB_PASSWORD`) | Flyway migrations only | Owns schema and tables; needs `CREATEROLE` to create `cadence_app` |
| `cadence_app` (`APP_DB_USER` / `APP_DB_PASSWORD`) | Runtime connection pool | `SELECT/INSERT/UPDATE/DELETE` only, not the owner, `NOBYPASSRLS` |

- Migration `V7__app_role_and_rls_functions.sql` creates `cadence_app` with the password from `APP_DB_PASSWORD`
  (Flyway placeholder `appDbPassword`; local default `cadence_app`; the migration fails if the password contains `'`
  or `$`). Change it later with `ALTER ROLE cadence_app PASSWORD '...'` and set `APP_DB_PASSWORD` to the same value.
- **Production:** set `APP_DB_PASSWORD` to a secret and `APP_PERSISTENCE_ALLOW_DEFAULT_DB_PASSWORD=false`
  (`app.persistence.allow-default-db-password`, `true` by default for local development). With `false` the application
  refuses to start, before any migration runs, while the datasource or Flyway password is empty or still the
  development default `cadence_app`. The `dev-headers` and `test` profiles are exempt.
- Every tenant table has the `tenant_isolation` policy (V4). Before each jOOQ statement inside a transaction the
  backend sets `app.current_tenant_id` from the request's tenant. Without it a query sees no rows and inserts are
  rejected. `FORCE ROW LEVEL SECURITY` is deliberately not enabled: the owner (migrations) keeps full access.
- The only cross-tenant reads go through `SECURITY DEFINER` functions executable only by `cadence_app`:
  `booking_token_lookup(hash)` (public booking links) and `outbox_claim(limit, max_attempts)` (outbox poller).
- `app.persistence.require-transaction=true` makes any jOOQ statement outside a Spring transaction fail (on in ITs);
  the default logs a warning once per call site.

### DeepSeek LLM (optional)

Committed config keeps `app.ai.provider=stub` so the app boots without a key. To use the real provider locally:

1. Put the key in **gitignored** `backend/modules/app/application-local.yml` (see `.env.example` for the env-var equivalent). `bootRun` includes the `local` profile when that file exists.
2. Or export `APP_AI_PROVIDER=deepseek` and `DEEPSEEK_API_KEY` before `bootRun`.

Model id: **`deepseek-flash`** (DeepSeek-V4.1-Flash, the current official fast/flash chat model). Override with `app.ai.model` / `APP_AI_MODEL`. There is no separate “Flash-4” id; `deepseek-v4-flash` is a retired alias that routes to the same model. `deepseek-chat` was retired on 2026-07-24.

Smoke test (needs the `dev-headers` profile, see Authentication):

```bash
curl -sS -X POST http://localhost:8080/api/app/ai/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: 00000000-0000-4000-8000-000000000001' \
  -d '{"message":"Summarize today in one sentence","locale":"en"}'
```

If `app.ai.provider=deepseek` and the key is missing, startup fails fast instead of silently using the stub.

ArchUnit + unit + Testcontainers ITs:

```bash
./gradlew :modules:app:test
```

jOOQ codegen (concatenated portable DDL in `db/jooq/schema.sql`):

```bash
./gradlew :modules:platform:jooqCodegen
```

## Run frontend

```bash
cd frontend
npm install
npm run web
```

- Console: `/dashboard`, `/calendar`, `/clients`, `/services`, `/specialists`, `/rules`, `/ai`
- Public booking: `/book/lumen-studio`
- Visit link: `/b/{id}?token=...`

i18n catalogs: `frontend/src/i18n/locales/{en,ru,ka,uk,cs}.json` (ru/en complete for UI; ka/uk/cs files exist so adding copy does not require code changes). Dark theme toggle lives in the console sidebar (`data-theme` on web).

## Production deployment

`docker-compose.prod.yml` is a standalone single-host stack for a pilot (it is not an override of the dev
`docker-compose.yml`). Configuration comes from an env file; start from [`.env.prod.example`](.env.prod.example), which
documents every variable.

```bash
cp .env.prod.example .env.prod          # gitignored; every secret is EMPTY in the example, fill each in (`openssl rand -hex 24`)
docker compose --env-file .env.prod -f docker-compose.prod.yml config   # fails naming the first empty/missing variable
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

### What runs where

| Service | Image | Host port | Notes |
|---------|-------|-----------|-------|
| `app` | built from `backend/Dockerfile` (JRE 25, non-root, read-only root fs) | `127.0.0.1:8080` | Spring profile `prod`; `/actuator/health` only |
| `keycloak` | `quay.io/keycloak/keycloak:26.2.5`, `start --import-realm` | `127.0.0.1:8081` | production mode, plain HTTP behind the proxy (`KC_PROXY_HEADERS=xforwarded`, `KC_HOSTNAME` = public URL) |
| `timescaledb` | `timescale/timescaledb:2.30.0-pg16` | none | application database, compose network only |
| `keycloak-db` | `postgres:16.9-alpine` | none | Keycloak's own database, separate from the application database |

Nothing is published on 5432/5433. The two published ports are bound to loopback only, for the reverse proxy.

- **Reverse proxy (not part of this repo):** TLS terminates in front. Route `https://auth.example.com` (= `KC_HOSTNAME`) to
  `127.0.0.1:8081` and the API host to `127.0.0.1:8080`, forwarding `X-Forwarded-For`, `-Proto` and `-Host`. Expose
  **only** `/realms/cadence/**` and `/resources/**` of Keycloak on the public hostname. Do not expose `/admin/**`,
  `/realms/master/**` (the admin realm: its token endpoint accepts the bootstrap admin's password) or anything else. Admin
  work is done from a separate, IP-allow-listed/VPN-only proxy location or hostname (Keycloak builds its admin links from
  `KC_HOSTNAME`, so a plain tunnel to `127.0.0.1:8081` redirects to the public name, which the rule below blocks).
  nginx:

  ```nginx
  location ~ ^/(realms/cadence/|resources/) { proxy_pass http://127.0.0.1:8081; # + X-Forwarded-* headers }
  location / { return 404; }          # blocks /admin, /realms/master, /metrics and the rest
  ```

  Caddy: `@kc path /realms/cadence/* /resources/*`, `reverse_proxy @kc 127.0.0.1:8081`, `respond 404` for everything else.
- **Admin realm brute-force protection (one-time, recommended):** Keycloak creates realm `master` with brute-force
  protection off and there is no startup flag or import file for it (the import only adds realm `cadence`). After the first
  start run this once; it changes one realm setting and is stored in the Keycloak database (verified with `kcadm.sh` on
  26.2.5):

  ```bash
  docker compose --env-file .env.prod -f docker-compose.prod.yml exec keycloak bash -c \
    '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8081 --realm master \
       --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" --config /tmp/kc.cfg &&
     /opt/keycloak/bin/kcadm.sh update realms/master -s bruteForceProtected=true -s failureFactor=5 \
       -s maxFailureWaitSeconds=900 -s minimumQuickLoginWaitSeconds=60 -s waitIncrementSeconds=60 --config /tmp/kc.cfg'
  ```

  Together with the proxy rule above this keeps the admin realm off the internet; the setting is a second layer.
- **Web console:** serve it from `CADENCE_WEB_ORIGIN` (e.g. `https://app.example.com`) built with
  `EXPO_PUBLIC_API_URL`, `EXPO_PUBLIC_OIDC_ISSUER=<KC_HOSTNAME>/realms/cadence`. That origin is the only CORS origin of the
  API (`APP_CORS_ALLOWED_ORIGINS`) and the only web redirect/origin of the Keycloak client.

### What the `prod` profile enforces

`application-prod.yml`: demo seed off; `APP_SECURITY_ISSUER_URI`, `APP_SECURITY_JWK_SET_URI` and `SPRING_DATASOURCE_URL` have no
defaults (an unresolved placeholder stops the startup); `APP_CORS_ALLOWED_ORIGINS` has no default either (compose derives it
from the required `CADENCE_WEB_ORIGIN`); the `dev-headers` mode is switched off; only `/actuator/health` is exposed.
`app.persistence.allow-default-db-password=false` activates `DbPasswordGuard`: the app refuses to start (before any
migration) when `APP_DB_PASSWORD` or `FLYWAY_DB_PASSWORD` is blank or still the dev default (`cadence_app` / `cadence`), or
when both are equal. Passwords must not contain `'` or `$`. A production process (`prod` profile) is never exempted by a
dev profile.

### Issuer and key set

Access tokens carry the public issuer `<KC_HOSTNAME>/realms/cadence`, which the backend validates exactly
(`APP_SECURITY_ISSUER_URI`). The signing keys are fetched from the internal address
`http://keycloak:8081/realms/cadence/protocol/openid-connect/certs` (`APP_SECURITY_JWK_SET_URI`): with an explicit
`jwk-set-uri` Spring does not run OIDC discovery against the issuer, so the app container never has to reach Keycloak
through the public proxy (no hairpin DNS/TLS from inside the network).

### Keycloak realm

`docker/keycloak/realm-cadence.prod.json` is imported on the first start of an empty Keycloak database. Compared with the
dev realm: no users, no `cadence-backend` client (it carried a committed secret and is unused), client `cadence-app` has
direct access grants off, implicit flow off, PKCE `S256`, redirect `<CADENCE_WEB_ORIGIN>/auth` and `cadence://auth`, web
origin and post-logout redirect only `CADENCE_WEB_ORIGIN` (the `${CADENCE_WEB_ORIGIN}` placeholder is substituted by
Keycloak's import from the container environment). Verified on Keycloak 26.2.5: with the variable unset the placeholder is
kept literally and the import then aborts on the redirect URI `${CADENCE_WEB_ORIGIN}/auth` ("A redirect URI is not a valid
URI"); that is a side effect of URI validation, not a guarantee, so do not rely on it: compose requires the variable (`:?`)
and refuses to start without it. The built-in client `admin-cli` is redeclared with direct access grants off (Keycloak
otherwise creates it in every realm with the password grant enabled, which would let anyone test passwords against realm
`cadence` outside the login UI); a password grant with `admin-cli` or `cadence-app` answers `unauthorized_client`;
the realm also declares `tenant_id` and `specialist_id` as admin-only managed user attributes (without that, Keycloak 26
silently drops unmanaged attributes set through the admin console); brute-force protection on, password policy 12 characters. The import
runs only when the realm does not exist yet: later edits of the file do not change a running realm (use the admin
console, or wipe `keycloak_db_data` on a fresh install).

### First owner user

The seed is off, so a tenant and its first user are created by hand (no onboarding API yet):

1. Create the tenant as the schema owner:

   ```bash
   docker compose --env-file .env.prod -f docker-compose.prod.yml exec timescaledb psql -U cadence -d cadence -c \
     "INSERT INTO tenant (id, slug, display_name, timezone, currency_code, country_code)
      VALUES (gen_random_uuid(), 'my-studio', 'My Studio', 'Asia/Tbilisi', 'GEL', 'GE') RETURNING id;"
   ```

2. In the Keycloak admin console (realm `cadence`, reached through the proxy or an SSH tunnel to `127.0.0.1:8081`, signed
   in with `KC_BOOTSTRAP_ADMIN_*`): Users, Add user. Set username, email, first and last name (required by the realm's user
   profile; a user without them is asked to complete the profile at first sign-in), enable email verified, then
   Credentials, set a password (12+ characters).
3. `tenant_id` = the UUID from step 1 (the form field is in the user's details; it validates the UUID format). Without it
   the API answers 403 `TENANT_CLAIM_MISSING`. For a master also `specialist_id`. Both are admin-only attributes of the
   realm's user profile: users cannot view or change them in the account console.
4. Role mapping tab: assign realm role `OWNER` (or `MASTER`).
5. Replace the bootstrap admin with a named admin and a strong password; remove `KC_BOOTSTRAP_ADMIN_*` from `.env.prod`
   afterwards if you like (it is read only on the first start).

### Rate limits behind the proxy

The client IP is the TCP peer unless the peer is listed in `APP_RATE_LIMIT_TRUSTED_PROXIES` (CIDRs, comma-separated). With
the proxy on the Docker host the peer is the compose network gateway; find it with
`docker network inspect cadence_default --format '{{(index .IPAM.Config 0).Gateway}}'` and set e.g.
`APP_RATE_LIMIT_TRUSTED_PROXIES=172.18.0.1/32`. Leave it empty and every client shares the proxy's address (and its
limit). Do not list ranges clients can reach directly. See [Public API rate limits](#public-api-rate-limits).

### Out of scope

Backups and restore (both volumes, `timescaledb_data` and `keycloak_db_data`, need a `pg_dump` or snapshot schedule and a
restore drill), log shipping, monitoring and alerting, TLS certificates and the proxy configuration, SMTP for Keycloak
(password reset e-mails), image vulnerability scanning, a non-superuser schema owner (TD-05), upgrades of pinned images.

## Phase 0 / 6 waitlist landing

Open `waitlist/index.html` (or any static file server). Submissions stay in `localStorage`.

## What is implemented

- **1A Booking Core:** tenancy + RLS SET LOCAL, catalog, slot calculator, status machine, snapshots, GiST exclusion, outbox poller SKIP LOCKED, audit hypertable, public booking + opaque token + .ics + owner task, CRM from public booking, MASTER per-resource checks, problem+details, OpenAPI.
- **1B:** dark theme, empty states, demo seed (3 masters / 8 services / 40 clients / ~200 visits) + Aura Atelier.
- **2:** Spring Batch metrics job + six dashboard metrics.
- **3:** `LlmProvider` Strategy (`stub` default, optional `deepseek` adapter), pseudonymization, draft confirm via Booking Engine, AI chat UI.
- **4:** `NotificationChannel` port, Trust NEW/HIGH/NORMAL/LOW, packages, Telegram stub + manual owner-task fallback, Spring Integration channel seam.
- **5:** growth signals persisted from metrics.
- **6:** waitlist evolved into a marketing landing.
- **7:** message files for ka/uk/cs (frontend + backend templates).

## Gaps (honest)

- Telegram bot is stubbed (no live Bot API / chat_id deep-link).
- LLM defaults to the stub. Set `APP_AI_PROVIDER=deepseek` plus `DEEPSEEK_API_KEY` (or gitignored `application-local.yml`) to use DeepSeek.
- Playwright E2E is not in CI; golden path is covered by `BookingCoreIT` (Testcontainers TimescaleDB).
- Mobile store builds are not published (in-bounds for MVP).
- Figma pixel-match was skipped (quota). Calendar is list+panel, not a dense week grid.
