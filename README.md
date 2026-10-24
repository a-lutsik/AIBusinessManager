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
