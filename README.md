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
- App API (local headers, OAuth2 off by default): http://localhost:8080/api/app/health
- OpenAPI: http://localhost:8080/openapi.yaml
- OAuth2: `--spring.profiles.active=oauth2` with Keycloak up

Local console calls send `X-Tenant-Id` (Lumen UUID `00000000-0000-4000-8000-000000000001`) and optional `X-Actor-Role` / `X-Specialist-Id`.

### DeepSeek LLM (optional)

Committed config keeps `app.ai.provider=stub` so the app boots without a key. To use the real provider locally:

1. Put the key in **gitignored** `backend/modules/app/application-local.yml` (see `.env.example` for the env-var equivalent). `bootRun` includes the `local` profile when that file exists.
2. Or export `APP_AI_PROVIDER=deepseek` and `DEEPSEEK_API_KEY` before `bootRun`.

Model id: **`deepseek-flash`** (DeepSeek-V4.1-Flash, the current official fast/flash chat model). Override with `app.ai.model` / `APP_AI_MODEL`. There is no separate “Flash-4” id; `deepseek-v4-flash` is a retired alias that routes to the same model. `deepseek-chat` was retired on 2026-07-24.

Smoke test (OAuth2 off):

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
