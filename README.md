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

The assistant runs a tool loop: the model asks for schedule, free slots, client profile and metrics through tools and may only propose changes as drafts; the owner confirms a draft in the UI and the booking engine validates and applies it ([ADR 0002](docs/adr/0002-ai-tool-loop-pseudonymization-drafts.md)). The model never sees client names, phones or UUIDs, only opaque references (`CLIENT_1`, `MASTER_2`, ...).

`app.ai.*` settings (defaults in `application.yml`; a missing, zero or negative value falls back to the default):

| Property | Default | Meaning |
|---|---|---|
| `provider`, `fallback-provider`, `model`, `reasoning-effort` | `stub`, none, `deepseek-flash`, `high` | provider id (`APP_AI_PROVIDER`), optional failover id, model name; the effort applies only with `deepseek.thinking` enabled |
| `deepseek.api-key`, `base-url`, `thinking`, `timeout` | none, `https://api.deepseek.com`, `disabled`, `90s` | `DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL`, `APP_AI_DEEPSEEK_THINKING`, `APP_AI_DEEPSEEK_TIMEOUT`; thinking other than `disabled` is refused at startup (not supported with tools) |
| `default-monthly-token-budget` | `1000000` | tokens per tenant and month when the operator set no budget in `ai_tenant_settings.monthly_token_budget`; the month is in the tenant's time zone; over budget the chat answers 429 `AI_BUDGET_EXCEEDED` |
| `max-tool-iterations`, `max-tool-calls-per-turn`, `max-drafts-per-turn` | `6`, `10`, `3` | limits of one chat turn; at the limit the model gets one last call without tools to word the answer |
| `max-tool-result-chars`, `max-history-messages`, `max-user-message-chars`, `max-completion-tokens` | `4000`, `20`, `4000`, `1024` | size of a tool result sent to the model, replies of the history sent, owner message length (longer: 400 `AI_MESSAGE_TOO_LONG`), reply tokens per call |
| `draft-ttl` | `PT24H` | a pending draft expires after this time |
| `retention`, `retention-cron` | `P30D`, `0 40 3 * * *` | nightly `AiRetentionJob` deletes conversations idle longer than `retention` with their messages, pseudonyms and drafts, per tenant; `ai_prompt_log` and `ai_tool_call` are dropped by their 30-day TimescaleDB retention policy |

Free text of a visit and client notes are not sent to the model by default; the owner can switch it on in the AI settings (the text is masked, the masking is best effort, see TD-37).

### Live check DeepSeek

The automated tests run against the stub, so the real wire format of DeepSeek is verified only by this manual check. It is the operator's own step: it spends tokens and sends masked data to an external provider. **Never put the key in the chat, in a commit or in a tracked file.**

1. Provide the key outside the repository: `export APP_AI_PROVIDER=deepseek DEEPSEEK_API_KEY=...` in the shell that starts `bootRun`, or put it in the gitignored `backend/modules/app/application-local.yml` (`app.ai.provider: deepseek`, `app.ai.deepseek.api-key: ...`). Keep thinking `disabled` (the default). Start the infrastructure and the backend with the `dev-headers` profile (see Authentication), seeded demo data, and open the console at `/ai` (or use the curl from the smoke test).
2. Sequence, in one conversation:
   1. Ask for today's schedule ("what do I have today?"). Expect a `get_schedule` call and an answer that lists the visits by the real names and times.
   2. Ask to book an existing client by name ("book Anna for a haircut with Nino tomorrow at 11:00", use a client from the seed or your data), then again in an inflected form ("book a visit for Anny", "запиши Анну на завтра"). Expect `find_free_slots` / `draft_booking` and a draft card in the chat, not a booking yet.
   3. Confirm the card. Expect the visit in the calendar; reject a second card and check it stays unbooked.
3. What to observe: the answers use real names (the references are restored on the way back); no `CLIENT_1`, `MASTER_1` or UUID text reaches the screen; a draft is created only for the named client; confirming a draft whose slot was taken meanwhile gives a readable error on the card; the token usage in the settings panel grows.
4. What the provider received and sent is in the database, masked, exactly as sent. Look for names and phones (the two count queries must return 0; replace the patterns with the real name and phone of the client you used):

```bash
docker exec -i cadence-timescaledb psql -U cadence -d cadence <<'SQL'
\set name '%Anna%'
\set phone '%995555123456%'
-- last calls: finish reason, tokens, latency, tool calls requested by the model
select created_at, finish_reason, prompt_tokens, completion_tokens, latency_ms, left(tool_calls_json, 120)
from ai_prompt_log order by created_at desc limit 10;
-- must return 0: the name or the phone (any spelling you know) in what was sent or received
select count(*) from ai_prompt_log where prompt ilike :'name' or response ilike :'name' or prompt like :'phone' or response like :'phone';
select count(*) from ai_tool_call where arguments ilike :'name' or result ilike :'name' or result like :'phone';
-- tools called in order, with status and duration
select created_at, tool_name, status, error_code, duration_ms, left(arguments, 100) from ai_tool_call order by created_at desc limit 20;
SQL
```

The owner role `cadence` bypasses RLS, so the queries see all tenants.

5. Known unverified points (the unit tests only mock the wire): the shape of `tool_calls` on assistant messages sent back to DeepSeek in the second call (`id`, `type: function`, `function.arguments` as a string) and the `tool` message with `tool_call_id`; `tool_choice: none` together with `tools` in the final forced call; a `system` message after tool results in that final call. A 400 from the provider on the second call of a turn points to one of these (the failed attempt is in `ai_prompt_log` with `finish_reason` `ERROR`, the cause is in the backend log).
6. If tool calls prove unreliable (the model ignores the tools, invents references, arguments are not valid JSON, or the format is rejected): do not widen the prompt. Follow plan B of ADR 0002 section 11: structured output (JSON the model returns, validated by us) through the same `LlmProvider` port, so only `DeepSeekLlmProvider` changes. Record the finding in `docs/tech-debt.md` (TD-41).

### Telegram channel

Clients who link Telegram get the booking confirmation and reminders about a day and about two hours before the visit
there; everyone else (no chat, channel off, delivery failed, the client blocked the bot) stays on a manual owner task with
the ready text, so no message is lost. One bot serves the whole installation; the tenant is found by the one-time link
token. The channel is off by default and needs the operator's steps below (the code never calls Telegram in tests: they use
a stub HTTP server).

1. Create the bot with @BotFather and note the token. The token is a secret: it comes from the environment only
   (`APP_TELEGRAM_BOT_TOKEN`), never from a committed file, and the application never logs or returns it.
2. Set `APP_TELEGRAM_ENABLED=true`, `APP_TELEGRAM_BOT_TOKEN`, `APP_TELEGRAM_BOT_USERNAME` (without `@`) and
   `APP_TELEGRAM_WEBHOOK_SECRET` (1-256 characters of `A-Z a-z 0-9 _ -`). With the channel enabled a missing or malformed
   value stops the startup. `prod` pins the Bot API URL to `https://api.telegram.org`.
3. Register the webhook once, with the same secret (the backend must be reachable over HTTPS):

   ```bash
   curl -sS "https://api.telegram.org/bot${APP_TELEGRAM_BOT_TOKEN}/setWebhook" \
     --data-urlencode "url=https://<api-host>/api/public/telegram/webhook" \
     --data-urlencode "secret_token=${APP_TELEGRAM_WEBHOOK_SECRET}" \
     --data-urlencode 'allowed_updates=["message"]'
   ```

   Telegram then sends the secret in `X-Telegram-Bot-Api-Secret-Token`; any other caller gets 401. The webhook sits inside
   the per-IP limit of the public API (`app.rate-limit.telegram-webhook-per-minute-per-ip`).
4. Linking: the booking-success page offers "Get reminders in Telegram" (a one-time `https://t.me/<bot>?start=<token>` link,
   valid 72 h, stored as a hash), and the owner can create one on the client card. Pressing Start in the bot is the client's
   consent to service messages (marketing consent is separate and unchanged). A client who blocks the bot (HTTP 403) is
   unlinked automatically.

The delivery journal is `notification_delivery` (`SENT`, `FAILED` with the reason, `SKIPPED_NO_CHAT`, `SKIPPED_DISABLED`,
`QUEUED_MANUAL`). Message texts live in `messages_<locale>.properties` (`client.telegram.*`) and use the client's
`preferred_locale` and the tenant's time zone. Reminders run in the backend (`app.telegram.reminder-poll-ms`), once per visit
start and kind, and only while the channel is enabled. A live check with a real bot is an operator step.

### Tests and code generation

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

## End-to-end tests (Playwright)

```bash
cd frontend
npm ci && npx playwright install chromium   # once
npm run e2e         # dev-headers: console and public booking against the backend with the dev-headers profile
npm run e2e:oidc    # Keycloak login (authorization code + PKCE) against the realm from docker/keycloak
npm run e2e -- -g smoke   # extra arguments go to `playwright test`
```

Each command builds and tears down everything itself (`frontend/scripts/e2e.mjs`, `docker-compose.e2e.yml`): TimescaleDB on a
tmpfs (and Keycloak for `e2e:oidc`) in the isolated compose project `cadence-e2e`, the backend through `bootRun`, and the web
bundle exported and served by `scripts/e2e-serve.mjs`. The AI provider is always the stub. Needs Docker and `JAVA_HOME` on a JDK 21
for Gradle: Gradle 8.14 does not run on JDK 25, also when that is the default of your shell. The runner stops with this message before it
starts anything. The JDK 25 compile toolchain is resolved by Gradle.

```bash
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/corretto-21.0.7/Contents/Home"
```

| Port | What |
|------|------|
| 15433 | TimescaleDB |
| 18080 | backend |
| 18081 | Keycloak (`e2e:oidc` only) |
| 19005 | web bundle of `npm run e2e` |
| 19006 | web bundle of `npm run e2e:oidc` |

- Isolation: the development database, its volumes and the ports of `docker compose up` (5433, 8080, 8081) are not touched. A
  busy E2E port stops the run with the owner of the port named; nothing foreign is killed. Whatever the run starts is stopped and
  the compose project is removed with its volumes at the end, also after a failure or Ctrl-C.
- Results are in `frontend/e2e-results/` (gitignored, cleared at the start of every run): `report/` (HTML report, open
  `report/index.html`), `screenshots/` (`<name>-1280.png` and `<name>-375.png`), `test-results/` (screenshots and traces of failed
  tests), `logs/` (backend, bundle export and server logs). `npm run e2e:oidc` starts with an empty directory too, so copy
  the results of the first run away if you need both.

## Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request:

- **backend**: Gradle on JDK 21 with the JDK 25 toolchain, `./gradlew build --no-build-cache` (jOOQ codegen, ArchUnit, unit tests and Testcontainers ITs). The build cache is off so cached results never hide a test run. A per-module test summary goes to the job summary; reports are uploaded on failure. Two guards keep a runner without a working Docker daemon from going green with the Testcontainers ITs skipped: with `CI` set (GitHub sets it) `AbstractTimescaleIT` fails instead of skipping when Docker is unreachable (on a developer machine without Docker the ITs are still skipped), and `.github/scripts/junit_summary.py --fail-on-skipped --min-tests 400 --require-class 'IT$'` fails the job when any test case was skipped, fewer than 400 ran (1115 at the time of writing; lower the number only when tests were removed on purpose) or no `*IT` class executed.
- **frontend**: Node 22, `npm ci`, `npm run lint` (zero warnings, includes the ban on hardcoded JSX text) and `npm run lint:selftest`, `npm run i18n:check`, `npm run typecheck`, `npx expo export --platform web`.
- **e2e**: Playwright on the isolated stack described in [End-to-end tests](#end-to-end-tests-playwright): `npm run e2e`, then `npm run e2e:oidc` (also when the first one failed), Chromium only. The results of each run are uploaded as the artifacts `e2e-results` and `e2e-results-oidc` (reports, screenshots, traces and logs, without the exported bundles; 7 days). It is not a required check: do not add it to branch protection until it has proven stable on the runners.
- **compose**: the production compose file must reject `.env.prod.example` (empty secrets) and accept a fully generated env file; the `web` service must be refused without `CADENCE_API_URL` and stay on `127.0.0.1:8082` with its URLs.
- **docker**: builds `backend/Dockerfile` without pushing.
- **web**: `scripts/web-host-check.sh` builds `docker/web/Dockerfile` and checks the rules of `docker/web/default.conf` on the real nginx: dynamic routes, the 404 page, leaving the web root, security and cache headers, non-root user, no `localhost` in the bundle, no token in the access log. Only docker and curl.

Dependabot (`.github/dependabot.yml`) proposes weekly updates for Gradle, npm, GitHub Actions and Docker base images; Expo/React majors are held back until an SDK upgrade.

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
| `web` | built from `docker/web/Dockerfile` (nginx, non-root, read-only root fs) | `127.0.0.1:8082` | static web export; dynamic routes and the 404 page in `docker/web/default.conf` |
| `keycloak` | `quay.io/keycloak/keycloak:26.2.5`, `start --import-realm` | `127.0.0.1:8081` | production mode, plain HTTP behind the proxy (`KC_PROXY_HEADERS=xforwarded`, `KC_HOSTNAME` = public URL) |
| `timescaledb` | `timescale/timescaledb:2.30.0-pg16` | none | application database, compose network only |
| `keycloak-db` | `postgres:16.9-alpine` | none | Keycloak's own database, separate from the application database |
| `backup` | `timescale/timescaledb:2.30.0-pg16` (client tools only) | none | scheduled `pg_dump` of both databases into `BACKUP_DIR` (see [Backups and restore](#backups-and-restore)) |

Nothing is published on 5432/5433. The three published ports are bound to loopback only, for the reverse proxy.

- **Reverse proxy (not part of this repo):** TLS terminates in front. Route `https://auth.example.com` (= `KC_HOSTNAME`) to
  `127.0.0.1:8081`, the API host (= `CADENCE_API_URL`) to `127.0.0.1:8080` and the console host (= `CADENCE_WEB_ORIGIN`) to
  `127.0.0.1:8082`, forwarding `X-Forwarded-For`, `-Proto` and `-Host`. Expose
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
- **Web console:** the `web` service serves the static export at `CADENCE_WEB_ORIGIN` (e.g. `https://app.example.com`), built
  with `EXPO_PUBLIC_API_URL=<CADENCE_API_URL>`, `EXPO_PUBLIC_OIDC_ISSUER=<KC_HOSTNAME>/realms/cadence` and
  `EXPO_PUBLIC_AUTH_MODE=oidc`. These are compiled into the bundle: an empty one stops the build, and a changed one needs
  `up -d --build web`. That origin is the only CORS origin of the API (`APP_CORS_ALLOWED_ORIGINS`) and the only web
  redirect/origin of the Keycloak client. `scripts/web-host-check.sh` builds the image and checks on its nginx that `/book/<slug>`,
  `/b/<id>?token=`, `/clients/<id>` and `/specialists/<id>` open their pages and that an unknown URL answers 404 with
  `+not-found` (also a CI job). Tokens of manage links stay out of the access log, which records paths without the query. HSTS
  belongs on the proxy, where TLS ends.

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

The seed is off, so a tenant and its first user are created by hand (no onboarding API for them; the catalog goes through the
console, see "Fill the catalog" below):

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

### Fill the catalog

Sign in to the console as the owner (`<CADENCE_WEB_ORIGIN>`):

1. **Services**: add each service with its duration, price (in the tenant's currency), buffers and color.
2. **Specialists**: add the masters, then open a master's card.
3. On the master's page: **Edit** a service in the matrix and switch on "Offers this service" (empty fields use the service's
   values), fill the **weekly schedule** (local time, `HH:MM`) and save it, add **exceptions** for days off and changed hours.
4. Open `<CADENCE_WEB_ORIGIN>/book/<slug>`: the service is listed and has free slots.

Nothing is deleted: an inactive service or master leaves the booking page, and their visits stay. The visits of an inactive master
can only be cancelled, not moved, and the master loses access to the console (403 `SPECIALIST_INACTIVE`) until reactivated; disabling
the user in Keycloak remains the operator's step. Booking rules are on their own screen. A refused value is explained on the form,
with its code. Catalog changes are recorded in `audit_log`.

The one step the console does not do is linking a master to a sign-in: `keycloakUserId` is the Keycloak user's id (the JWT `sub`)
and is set by the operator through the owner API; `null`, which is what the console sends, keeps the stored link, and
`DELETE $API/api/app/specialists/$SPECIALIST_ID/keycloak-link` removes it. One sign-in links to one master of a tenant.
Removing the link only ends the sign-in found by the token's `sub`: a token with the `specialist_id` attribute keeps working. To
revoke access for certain, deactivate the master (403 `SPECIALIST_INACTIVE`) or disable the user in Keycloak.
The same API suits scripted onboarding. With the owner's bearer token (see the smoke check below for how to get one), `API` is the
app origin; `id: null` creates, the response carries the new `id`, money is in minor units (`priceMinor: 8000` is 80.00):

```bash
H=(-H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json")
curl -sS -X PUT "$API/api/app/services" "${H[@]}" -d '{"id":null,"name":"Haircut","description":null,"durationMinutes":60,"priceMinor":8000,"bufferBeforeMinutes":0,"bufferAfterMinutes":10,"color":"#0f766e","active":true,"publicVisible":true}'
curl -sS -X PUT "$API/api/app/specialists" "${H[@]}" -d '{"id":null,"displayName":"Nino","keycloakUserId":null,"calendarColor":"#7c3aed","active":true}'
# $SERVICE_ID and $SPECIALIST_ID are the "id" values from the two responses
curl -sS -X PUT "$API/api/app/specialists/$SPECIALIST_ID/matrix" "${H[@]}" -d '{"serviceId":"'$SERVICE_ID'","offered":true,"durationMinutesOverride":null,"priceMinorOverride":null,"bufferBeforeMinutesOverride":null,"bufferAfterMinutesOverride":null}'
# the whole weekly schedule is replaced; weekday 1=Mon..7=Sun, minutes from midnight (600 = 10:00, end exclusive)
curl -sS -X PUT "$API/api/app/specialists/$SPECIALIST_ID/schedule" "${H[@]}" -d '[{"id":null,"weekday":1,"startMinute":600,"endMinute":1140},{"id":null,"weekday":2,"startMinute":600,"endMinute":1140}]'
# a day off (kind DAY_OFF) or changed hours (kind SHIFT with startMinute/endMinute)
curl -sS -X PUT "$API/api/app/specialists/$SPECIALIST_ID/exceptions" "${H[@]}" -d '{"id":null,"date":"2031-12-31","kind":"DAY_OFF","startMinute":null,"endMinute":null,"note":"Holiday"}'
```

All rules and error codes are in `openapi.yaml`.

### Smoke check of a deployment

`scripts/deploy-smoke.sh` checks a running deployment from the outside, through the public URLs. Run it after the first deploy
and after every change of the proxy, `KC_HOSTNAME` or `CADENCE_WEB_ORIGIN`; it needs only `bash` and `curl` (`openssl` for the
token and port checks) and changes nothing on the target.

```bash
ACCESS_TOKEN=<bearer token of the first owner> \
scripts/deploy-smoke.sh --app-url https://api.example.com --kc-url https://auth.example.com \
                        --web-origin https://app.example.com --strict
# on the server itself, additionally: --check-ports
```

`--kc-url` is `KC_HOSTNAME`, `--web-origin` is `CADENCE_WEB_ORIGIN`. The token is optional but needed once: sign in to the console as
the first owner and copy the bearer token from the browser's network tab (valid for 5 minutes; use `--token-file FILE` or the
environment variable, not a command-line value). The script never puts the token on a command line: it is sent from a `0600`
header file in the script's private temp directory (removed on exit), so it does not show up in `ps`. An unreadable `--token-file`
is a `[FAIL]`; without `openssl` the token issuer check cannot run, which is a `[FAIL]` with `--strict` and a `[WARN]` otherwise.
Checks, each printed as `[PASS]`/`[FAIL]`/`[WARN]`:

| Area | Expectation |
|------|-------------|
| URLs | both public URLs are `https` (plain `http` is only accepted on loopback) |
| API | `/actuator/health` 200 `UP`; `/actuator/env`, `/metrics`, `/beans` not 200; `/api/app/me` without or with a garbage token 401 |
| CORS | preflight from the console origin 200 with `Access-Control-Allow-Origin` = the origin; from a foreign origin 403 |
| Keycloak | discovery `issuer` equals `<KC_HOSTNAME>/realms/cadence` and all its endpoints start with `KC_HOSTNAME` (proves the proxy's `X-Forwarded-*` handling); password grant for `cadence-app` and `admin-cli` answers `unauthorized_client` |
| Admin surface | `/realms/master/...` and `/admin/...` answer 403/404 through the public URL; any other answer is a `[WARN]` (a `[FAIL]` with `--strict`) |
| Token | token `iss` equals the expected issuer, and `/api/app/me` with it answers 200 |
| Ports (`--check-ports`) | nothing on 5432/5433 on a non-loopback address; 8080/8081 bound to loopback only |

Exit status 0 = no failed check (and, with `--strict`, no warning); the last line is `DEPLOY SMOKE: PASS` or `FAIL`. Run against a
local prod stack (no proxy) the admin-surface checks, the plain-`http` notes and a foreign process on 5432 show up as warnings:
that is expected there and is why the real production run should use `--strict`. The script does not check the `kcadm.sh` step for
realm `master` (it needs admin credentials) or the Expo build for the production URL; do both by hand.

### Rate limits behind the proxy

The client IP is the TCP peer unless the peer is listed in `APP_RATE_LIMIT_TRUSTED_PROXIES` (CIDRs, comma-separated). With
the proxy on the Docker host the peer is the compose network gateway; find it with
`docker network inspect cadence_default --format '{{(index .IPAM.Config 0).Gateway}}'` and set e.g.
`APP_RATE_LIMIT_TRUSTED_PROXIES=172.18.0.1/32`. Leave it empty and every client shares the proxy's address (and its
limit). Do not list ranges clients can reach directly. See [Public API rate limits](#public-api-rate-limits).

### Erasing a client on request

Right to erasure (PRODUCT_PLAN §10): the owner opens the client's card and uses **Anonymize client** (`POST
/api/app/clients/{id}/anonymize`, OWNER only). It is irreversible and refused with `CLIENT_HAS_UPCOMING_VISITS` while the client
has a pending or confirmed visit that has not ended: cancel those first.

- **Goes:** name, phone, notes, tags, locale, Telegram chat and consent on the profile; the typed name and note on every visit;
  manage links (revoked at once, a new one is refused); owner tasks about the client; the trust override and its reason; the
  owner's package names; every AI conversation that names the client, with its messages, drafts and logs.
- **Stays, without the person:** visits and their journal, the profile's counters and the package balances (unused sessions can no
  longer be used, the confirmation says how many), so metrics of past periods do not move. `audit_log` holds ids and counters only and records `client.anonymize`.
- The same phone can book again and becomes a new client with no link to the old profile.
- **Stays until its own retention:** database backups (`BACKUP_RETENTION_DAYS`, offsite by your own schedule); state this in the
  privacy notice. Two limits are in `docs/tech-debt.md`: a name typed in an AI chat that was not recognised at the time (TD-36,
  TD-79) and the masked phone in a rate-limit audit record (TD-80).
- **Out of reach once sent:** prompts that already went to a real LLM provider (masked names, but free text such as notes and reasons)
  stay with the provider under its own retention, whatever is erased here. With `APP_AI_PROVIDER=stub` this does not exist. Before a
  real provider, this must be in the privacy notice and covered by the DPA.
- **Logs outside the application:** PostgreSQL's server log can quote a phone in the `DETAIL` of a failed statement, and an access
  log of your reverse proxy holds the query string of manage links unless you strip it. Anonymization does not reach them: keep
  their retention no longer than the privacy notice says, and leave `$args` out of the proxy's log format.

### Backups and restore

**What is backed up.** The `backup` service writes one directory per run, `BACKUP_DIR/<UTC timestamp>/` (default
`./backups/20261025T101500Z/`), holding:

| File | Content |
|------|---------|
| `cadence.dump` | `pg_dump -Fc` of the application database (TimescaleDB: tables, hypertables, chunks, RLS policies, grants, the V11 job) |
| `keycloak.dump` | `pg_dump -Fc` of Keycloak's database (realm, users, credentials, sessions). **Sensitive:** it contains the realm's signing keys, the client secrets and every user's password hash, i.e. everything needed to mint valid tokens or attack passwords offline |
| `cadence-roles.sql` | `pg_dumpall --roles-only --no-role-passwords` of the application database server (roles are cluster-level and not part of a database dump) |
| `cadence.manifest`, `keycloak.manifest` | row counts of every table, Flyway version, TimescaleDB version, hypertable/chunk/RLS/job counts, taken in the same snapshot as the dump |
| `SHA256SUMS` | checksums of the files above |

Role passwords are deliberately **not** in the dump: the file would otherwise carry the SCRAM verifiers of the schema owner
and of `cadence_app` into every offsite copy. The passwords live in `.env.prod`; back that file up separately and
encrypted (without it a restored stack cannot be started). Treat the whole `BACKUP_DIR` like `.env.prod`: `keycloak.dump` is token-minting
material (see the table), so `BACKUP_DIR` may leave the host only encrypted, with a key that never travels with the copy. Each database is dumped inside one exported snapshot
(`pg_dump --snapshot`), so the dump and its manifest describe the same instant while the application keeps writing. A run is
written to a temporary directory and renamed when everything succeeded: a timestamp-named directory is always complete.

**Schedule and retention.** The first backup runs when the stack starts, then every `BACKUP_INTERVAL` (default `6h`; seconds or
`30m`/`6h`/`1d`, minimum 60 s). Backups older than `BACKUP_RETENTION_DAYS` (default 14) are deleted after a successful run (so an erased or anonymised client disappears from local backups after at most that many days, and from offsite copies only after their own retention — state both periods in the privacy notice, PRODUCT_PLAN §10);
the newest `BACKUP_KEEP_MIN` (default 3) are always kept. A failed run is logged (`ERROR ...` in `docker compose logs backup`),
retried after 5 minutes, and the service is `unhealthy` in `docker compose ps` until a run succeeds (a failed run never
removes older backups). The service is also `unhealthy` when the newest complete backup (read from its directory name, so no file has to
be writable) is older than two intervals plus 15 minutes, which catches a disk that is too full to even record a failure. Worst-case data
loss is therefore one interval: lower `BACKUP_INTERVAL` if that is too much. A backup
right now, with the same exit status contract (non-zero on failure):

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml run --rm backup once
```

The first run right after a fresh install fails until the application has applied its migrations (the manifest reads the Flyway
history); the retry after 5 minutes succeeds. It is created by Docker as root. To own the files as your host user, create it yourself and
set `BACKUP_UID`/`BACKUP_GID`.

**Disk space.** Put `BACKUP_DIR` on a different disk or volume than the database volumes (`timescaledb_data`, `keycloak_db_data`)
whenever you can: a backup disk that fills up must not be able to take the databases down with it. Before every dump the service checks
the free space of `BACKUP_DIR`: it must be at least `BACKUP_MIN_FREE_MB` (default 1024) and at least the size of the newest backup. If
not, the service first deletes old backups (by retention, then the oldest ones, never going below the newest `BACKUP_KEEP_MIN`) and
only then dumps; if there is still not enough room the run fails with an `ERROR not enough disk space ...` line, the service turns
`unhealthy`, and nothing newer than the kept backups is touched. Choose `BACKUP_MIN_FREE_MB` as at least twice the size of one backup,
and keep `BACKUP_KEEP_MIN` x backup size well below the disk size. Every write of a run is checked and the finished files are read back
against `SHA256SUMS`, so a short write fails the run instead of leaving a corrupt directory.

**Offsite copy is the operator's job.** Backups on the same host die with the host (disk failure, deletion, ransomware, a
compromised server). Copy `BACKUP_DIR` and `.env.prod` to storage under a different failure domain, encrypted, on a schedule
that matches `BACKUP_INTERVAL`, and alert when it fails. One concrete example with [restic](https://restic.net) to
S3-compatible storage (client-side encrypted, deduplicated; the repository was created once with `restic init`), as a daily
cron entry of a user that can read `BACKUP_DIR`:

```bash
0 3 * * *  RESTIC_REPOSITORY=s3:https://s3.example.com/cadence-backups RESTIC_PASSWORD_FILE=/root/.restic-pass \
           AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... \
           restic backup /var/backups/cadence /srv/cadence/.env.prod && restic forget --keep-daily 14 --keep-weekly 8 --prune
```

Do not replace this with a plain `rclone sync` or any copy that stores the files readable by the storage provider: `keycloak.dump` is
token-minting material. If you prefer rclone, the destination must be an [`rclone crypt`](https://rclone.org/crypt/) remote. This repository does not
implement the offsite copy. An offsite copy that was never restored is not a backup either: run the drill below on a copy fetched back from it.

**Restore drill: do it before the pilot and then regularly (recommended: after every release that adds a migration, and at least
monthly).** `scripts/restore-drill.sh` restores a backup into throwaway containers (no network, own anonymous volumes, removed on
every exit path) and compares the result with the manifest recorded at backup time. It does not touch the running stack, so it
can run on the production host (needs the `docker` CLI and `openssl`):

```bash
BACKUP_DIR=/var/backups/cadence scripts/restore-drill.sh            # newest backup
scripts/restore-drill.sh 20261025T101500Z                            # a timestamp below BACKUP_DIR
scripts/restore-drill.sh /mnt/restored-from-offsite/20261025T101500Z   # a directory
```

It prints one `[PASS]`/`[FAIL]`/`[WARN]` line per check and ends with `RESTORE DRILL: PASS` (exit 0) or `RESTORE DRILL: FAIL`
(exit 1). It checks: file checksums; the TimescaleDB extension version of the restore image equals the one at backup time (a
logical restore needs the same version); the restore finished without unexpected errors; Flyway version, the row count of every
`public` table (including `tenant`, `client`, `appointment`, `audit_log`, `outbox_event`), hypertable and chunk counts, RLS
table and policy counts equal the manifest; all chunks are in schema `cadence_chunks`; the V11 job `cadence_revoke_chunk_privileges`
exists and is scheduled; `cadence_app` is still subject to RLS (no tenant context: 0 rows; with a tenant context: that
tenant's rows) and has no privileges on the chunk schema; Keycloak's counts and its realms. A `[WARN]` means the backup had no
rows in a place the check needs (e.g. an empty install cannot demonstrate RLS); it does not fail the drill.

**Restore for real (disaster recovery).** Procedure for the application database, from the TimescaleDB documentation
([logical backup and restore, "Entire database"](https://www.tigerdata.com/docs/self-hosted/latest/backup-and-restore/logical-backup)):
create the roles, create the database with the extension, call `timescaledb_pre_restore()`, `pg_restore` (no `-j`), then
`timescaledb_post_restore()`. The restore needs the same TimescaleDB version as the backup (`timescale/timescaledb:2.30.0-pg16`
pinned in the compose file; the manifest records the version). On a new host: install Docker, restore `.env.prod`, copy the
backup directory back, then start only the databases: `docker compose --env-file .env.prod -f docker-compose.prod.yml up -d timescaledb keycloak-db`.

```bash
C="docker compose --env-file .env.prod -f docker-compose.prod.yml"
B=20261025T101500Z                       # the backup to restore
$C stop app keycloak backup              # nothing may write while the databases are replaced
# 1. verify, restore roles, application database (this DROPS the current database `cadence` and Keycloak's contents)
$C run --rm --no-deps --entrypoint sh backup -c '
  set -e; cd /backups/'$B' && sha256sum -c SHA256SUMS
  export PGPASSWORD=$APPDB_OWNER_PASSWORD
  psql -h timescaledb -U cadence -d postgres -f cadence-roles.sql || true      # "role cadence already exists" is expected
  psql -h timescaledb -U cadence -d postgres -c "DROP DATABASE IF EXISTS cadence WITH (FORCE)" -c "CREATE DATABASE cadence"
  psql -h timescaledb -U cadence -d cadence -c "CREATE EXTENSION IF NOT EXISTS timescaledb" -c "SELECT timescaledb_pre_restore()"
  # pg_restore exits 1 when it ignored an error (warnings do not change the exit status). Under `set -e` that would stop the
  # script BEFORE timescaledb_post_restore() and leave the database in restoring mode, so the status is read explicitly: the one
  # expected error (the dump re-creates the extension the target already has) is tolerated, anything else stops here.
  rc=0; pg_restore -h timescaledb -U cadence -d cadence cadence.dump 2>/tmp/restore.err || rc=$?
  cat /tmp/restore.err
  if [ "$rc" -ne 0 ] && grep -v -e "ERROR:  extension .timescaledb. already exists" -e "must be owner of extension timescaledb" \
        -e "^Command was: CREATE EXTENSION" -e "^Command was: COMMENT ON EXTENSION" \
        -e "errors ignored on restore: [12]$" -e "^$" /tmp/restore.err | grep -q .; then
    echo "unexpected pg_restore messages above: NOT calling timescaledb_post_restore(); investigate" >&2; exit 1
  fi
  psql -h timescaledb -U cadence -d cadence -c "SELECT timescaledb_post_restore()"
  # 2. Keycloak database
  export PGPASSWORD=$KCDB_PASSWORD
  psql -h keycloak-db -U keycloak -d postgres -c "DROP DATABASE IF EXISTS keycloak WITH (FORCE)" -c "CREATE DATABASE keycloak"
  pg_restore --exit-on-error -h keycloak-db -U keycloak -d keycloak keycloak.dump
'
# 3. the roles file has no passwords: set the runtime role's password again (value of APP_DB_PASSWORD in .env.prod).
#    Quotes around the value are stripped the way compose does; the statement travels on stdin (printf is a shell builtin), so
#    the password is never an argument of any process and cannot be seen in `ps`. The value cannot contain ' or $ (see .env.prod.example).
APP_PW=$(sed -n 's/^APP_DB_PASSWORD=//p' .env.prod | head -n 1 | sed -e "s/^[\"']//" -e "s/[\"']\$//")
case "$APP_PW" in
  '' | *"'"*) echo "APP_DB_PASSWORD missing or unusable in .env.prod: set the role password by hand" >&2 ;;
  *) printf "ALTER ROLE cadence_app PASSWORD '%s';\n" "$APP_PW" | $C exec -T timescaledb psql -U cadence -d cadence -v ON_ERROR_STOP=1 ;;
esac
unset APP_PW
$C up -d                                 # app, keycloak and backup again
```

Afterwards check `/actuator/health`, sign in once, and compare a few counts with `$B/cadence.manifest`. `pg_restore` prints
nothing on success; the `NOTICE`/`WARNING` lines of `psql` are harmless, an `ERROR` is not (stop and investigate before starting
the app). `pg_restore` warnings never change its exit status; when it exits non-zero, the step-1 script goes on to
`timescaledb_post_restore()` only if every message is the expected "extension already exists" one, and otherwise stops before it. Do not use `pg_restore -j` with TimescaleDB. The drill performs exactly steps 1 and 2 in an empty server.

### Out of scope

The offsite copy of the backups (see [Backups and restore](#backups-and-restore)), log shipping, monitoring and alerting, TLS certificates and the proxy configuration, SMTP for Keycloak
(password reset e-mails), image vulnerability scanning, a non-superuser schema owner (TD-05), upgrades of pinned images.

## Phase 0 / 6 waitlist landing

Open `waitlist/index.html` (or any static file server). Submissions stay in `localStorage`.

## What is implemented

- **1A Booking Core:** tenancy + RLS SET LOCAL, catalog, slot calculator, status machine, snapshots, GiST exclusion, outbox poller SKIP LOCKED, audit hypertable, public booking + opaque token + .ics + owner task, CRM from public booking, MASTER per-resource checks, problem+details, OpenAPI.
- **1B:** dark theme, empty states, demo seed (3 masters / 8 services / 40 clients / ~200 visits) + Aura Atelier.
- **2:** Spring Batch metrics job + six dashboard metrics.
- **3:** AI manager (stage 05, [ADR 0002](docs/adr/0002-ai-tool-loop-pseudonymization-drafts.md)): multi-turn `LlmProvider` port (`stub` default, optional `deepseek` adapter), tool loop (schedule, free slots, client profile, metrics, draft booking / reschedule / cancel), pseudonymization of names, phones and ids with the map in the database, drafts confirmed by the owner and validated by the Booking Engine, monthly token budget, `ai_prompt_log` / `ai_tool_call` logs, nightly `AiRetentionJob`, AI chat UI with draft cards and settings.
- **4:** `NotificationChannel` port, Trust NEW/HIGH/NORMAL/LOW, packages, Telegram stub + manual owner-task fallback, Spring Integration channel seam.
- **5:** growth signals persisted from metrics.
- **6:** waitlist evolved into a marketing landing.
- **7:** message files for ka/uk/cs (frontend + backend templates).

## Gaps (honest)

- Telegram bot is stubbed (no live Bot API / chat_id deep-link).
- LLM defaults to the stub. Set `APP_AI_PROVIDER=deepseek` plus `DEEPSEEK_API_KEY` (or gitignored `application-local.yml`) to use DeepSeek. The DeepSeek tool-call format is confirmed only by the operator's live check ([Live check DeepSeek](#live-check-deepseek), TD-41); thinking mode is not supported with the tool loop.
- AI: name masking is best effort (an unrecognised or misspelled name in an owner message reaches the provider, TD-36; notes are off by default and masked by denylist and regex, TD-37); the monthly token budget is set by the operator in the database, there is no admin surface (TD-42). Full list in `docs/tech-debt.md`.
- Playwright E2E runs in CI as a separate, non-required `e2e` job; the booking core is also covered by `BookingCoreIT` (Testcontainers TimescaleDB).
- Mobile store builds are not published (in-bounds for MVP).
- Figma pixel-match was skipped (quota). Calendar is list+panel, not a dense week grid.
