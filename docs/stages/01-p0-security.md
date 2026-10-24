# Этап 01 — P0: безопасность и целостность данных

Цель: закрыть P0-блокеры из `docs/REVIEW_2026-10-23.md` §3.2, чтобы продукт можно было показать пилоту с реальными данными.
Критерий готовности этапа:
- без валидного JWT `/api/app/**` отвечает 401; JWT без `tenant_id` или без роли — 403; заголовки `X-Tenant-Id`/`X-Actor-*` игнорируются вне профиля `dev-headers`;
- приложение подключается к БД ролью `cadence_app`, которая не владеет таблицами; запрос без tenant-контекста не видит строк; арендатор A не читает и не пишет строки B;
- в БД нет сырых токенов брони; ссылку владелец получает разовой выдачей нового токена;
- merge клиентов перепривязывает визиты, пакеты, задачи и складывает агрегаты; исправление NO_SHOW↔COMPLETED корректирует агрегаты и пишет отдельный audit;
- гонка за слот даёт 409 `SLOT_OCCUPIED`, а не 500;
- компиляция, ArchUnit, юнит-тесты зелёные; интеграционные тесты написаны и зелёные (или гейт явно открыт).

Риск: HIGH (авторизация, изоляция арендаторов, целостность данных). Независимое ревью обязательно.

## Задачи

- [x] T1 OAuth2 по умолчанию, строгий `TenantContextFilter`, профиль `dev-headers`, IT на JWT — исполнитель: worker — тесты: в составе задачи — проверил Opus: ☑ (57 юнит-тестов зелёные; `BookingCoreIT`, `AppApiAuthIT`, `DevHeadersIT` не запускались из-за диска Docker)
- [x] T2 (проверил Opus: ☑ юнит-тесты; 8 утверждений о поведении TimescaleDB не доказаны до запуска IT) Роль `cadence_app`, гранты, SECURITY DEFINER для токенов и outbox, jOOQ-listener RLS, outbox с savepoint и лимитом попыток, IT изоляции — исполнитель: complex_coder — проверил Opus: ☐
- [x] T3 (проверил Opus: ☑ юнит-тесты; регулярка V8 проверена на локальном PG17; `AccessLinkIT` не запускался) Сырой токен: owner_task без токена, `POST /api/app/appointments/{id}/access-link`, миграция-скраб — исполнитель: worker — проверил Opus: ☐
- [x] T4 (проверил Opus: ☑ 92 юнит-теста зелёные; `ClientMergeIT` не запускался; поглощённый профиль обнуляет счётчики против двойного подсчёта) Merge клиентов (use-case в `app`), откат агрегатов при исправлении статуса — исполнитель: worker — проверил Opus: ☐
- [x] T5 (проверил Opus: ☑ 117 юнит-тестов зелёные; `SlotRaceIT` не запускался) Маппинг exclusion/unique violation в 409, IT гонки — исполнитель: worker — проверил Opus: ☐
- [x] T6 (review: runtime-логин не проверен, Keycloak не поднимается) Фронтенд: OIDC-логин (PKCE) через Keycloak, Bearer вместо заголовков роли, кнопка «скопировать ссылку» по контракту T3 — исполнитель: worker — проверил Opus: ☐

Статусы: todo → in_progress → review → done | returned.

## Контракты, общие для задач

- Профиль `dev-headers`: `app.security.oauth2-enabled=false`, `app.security.header-auth-enabled=true`. Без него header-режим выключен.
- `POST /api/app/appointments/{id}/access-link` (OWNER) → `{ "managePath": "/b/{id}?token=...", "expiresAt": "<ISO>" }`. Каждый вызов выпускает новый токен; сырое значение нигде не сохраняется.
- Задача `NEW_BOOKING`: `appointmentId` заполнен, `link = null`, `copyText` содержит плейсхолдер `{manageLink}`, который фронтенд подставляет из ответа access-link.
- DB: Flyway — владелец `cadence`; runtime datasource — `cadence_app` (пароль из `APP_DB_PASSWORD`, по умолчанию `cadence_app` для локальной разработки).

## Проверка этапа
- [x] Все задачи ✔ (Opus)
- [x] Регрессия зелёная: юнит 140/140, IT 43/43, 0 skipped (`./gradlew test --rerun-tasks`, 2026-10-24); frontend typecheck ✔
- [x] Независимое ревью (reviewer): 1 blocker, 2 major, 7 minor; blocker, M2 и m1–m4 исправлены, M1 и m5–m7 вынесены в `docs/tech-debt.md`; повторное ревью исправлений — self-review Opus (reduced assurance)
- [ ] Приёмка архитектором (по политике): ☐ принято / ☐ возвращено — гейт IT закрыт, ждёт приёмки

## Журнал
- 2026-10-24: этап открыт. Docker VM заполнен на 100% (`No space left on device`), IT на Testcontainers не стартуют до освобождения места.
- 2026-10-24: T1–T6 реализованы. Ревью нашло blocker: `normalized_phone` анонимизированного клиента (41 символ) не помещался в VARCHAR(32), merge и anonymize всегда падали — воспроизведено на PG17, исправлено (`AnonymizedPhone`, 27 символов). Исправлены гонка переходов статуса (`FOR UPDATE` + условный UPDATE → 409 `CONCURRENT_MODIFICATION`), блокировки при merge, декодированный путь в фильтре, `Error` в outbox, guard дефолтного пароля БД, тесты без `assumeTrue`.
- Проверено на реальном PostgreSQL 17 (без Timescale): регулярка V8, V1–V9 без Timescale-строк, гранты и default privileges, `outbox_claim`/`booking_token_lookup`, длина анонимного телефона, порядок блокировок merge, проверка пароля V7 через Flyway 12.4.
- Не проверено: всё, что требует TimescaleDB и Spring-контекста целиком (39 IT), реальный JWT от Keycloak, OIDC-логин во фронтенде.
- 2026-10-24: по разрешению пользователя выполнены `docker container prune` и `docker image prune` (освобождено 487 МБ, dangling-образов нет). TimescaleDB всё ещё не стартует (`No space left on device`): место занимают тома (40,9 ГБ, неактивные) и образы с тегами (ollama 6 ГБ, старые timescale ~4 ГБ). Гейт IT остаётся открытым.
- 2026-10-24: по разрешению пользователя удалены образы `ollama/ollama:latest`, `timescale/timescaledb:2.17.2-pg16`, `2.17.2-pg17` (свободно 8,9 ГБ). Первый прогон IT: 35/39. Найдены и исправлены: (1) тестовый хелпер `ClientMergeIT` не передавал `marketingConsent` → 400; (2) **реальный обход RLS**: `cadence_app` читал 228 чужих строк напрямую из чанков TimescaleDB. Исправлено миграциями V10 (чанки в закрытую схему `cadence_chunks`, инвалидация кеша для уже открытых сессий через `set_chunk_time_interval`) и V11 (отзыв прав `cadence_app` на чанки + задача TimescaleDB раз в минуту — закрывает утечку значений через `pg_stats`). Отозвать USAGE на `_timescaledb_internal` нельзя: расширению она нужна в каждой сессии (проверено). V10 прошёл отдельное независимое ревью; его замечания (устаревшие сессии, тест на схему всех hypertables, невакуумная проверка грантов) закрыты. Итоговый прогон: 140 юнит + 43 IT, 0 падений, 0 пропусков. Гейт IT закрыт.
