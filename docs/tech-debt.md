# Технический долг

## Этап 01 — P0 безопасность (2026-10-24)

| ID | Что | Риск | Что сделать |
|---|---|---|---|
| TD-01 | ~~39 IT не запускались~~ — закрыто 2026-10-24: 43 IT зелёные | — | — |
| TD-02 | Остаточный риск изоляции чанков TimescaleDB (прямое чтение закрыто V10, привилегии на чанки отозваны V11): новый чанк до ближайшего запуска job `cadence_revoke_chunk_privileges` (раз в минуту) держит скопированные гранты `cadence_app`; в это окно `pg_stats` после ANALYZE может показать значения колонок. Новые hypertables нужно создавать с `associated_schema_name => 'cadence_chunks'`; V10 правит каталог напрямую — перепроверить после `ALTER EXTENSION timescaledb UPDATE` | LOW | Следить за `RlsIsolationIT` (приватная схема, отсутствие привилегий, `pg_stats`); при необходимости сократить интервал job или включить RLS на чанках |
| TD-03 | Нет проверки audience/azp у JWT: принимается любой токен realm `cadence` с нужными claims | MEDIUM | Добавить валидатор audience (`cadence-backend`) и маппер аудитории в Keycloak |
| TD-04 | `app.persistence.allow-default-db-password` по умолчанию `true` | MEDIUM | В прод-профиле `false` + `APP_DB_PASSWORD`; пароль с текстом `$cadence_pw$` ломает dollar-quote V7 |
| TD-05 | SECURITY DEFINER функции принадлежат суперпользователю `cadence` в docker-compose/Testcontainers | LOW | Отдельная не-суперпользовательская роль-владелец схемы |
| TD-06 | `issueAccessLink` не отзывает ранее выданные токены: каждый клик «скопировать» создаёт ещё одну живую ссылку на 365 дней | LOW | Продуктовое решение: отзывать предыдущие owner-выданные токены или сократить срок |
| TD-07 | Копирование в буфер после await сети может быть отклонено Safari | LOW | Получать ссылку заранее при рендере задачи |
| TD-08 | Визиты сида создаются со `statusOverride` и не увеличивают агрегаты CRM; исправление статуса на них уменьшает реальные счётчики | LOW | Сид через обычные переходы или пересчёт агрегатов после сида |
| TD-09 | `set_config` выполняется перед каждым SQL-оператором (лишний round trip) | LOW | Кеш с инвалидацией на savepoint, если станет узким местом |
| TD-10 | ~~Rate limit доверял `X-Forwarded-For` без списка доверенных прокси~~ — закрыто 2026-10-24: IP = TCP-пир, XFF только от `app.rate-limit.trusted-proxies`; лимиты по плану (слоты 5/мин/IP, 3 визита/сутки на телефон, 20/час на слаг) + 20/час на IP как единственный предел для попыток без разбираемого телефона; 429 `RATE_LIMITED` с `Retry-After`, нарушения в audit `public.rate_limited` (маскированные IP/телефон), лимит числа ключей. Состояние в памяти одного инстанса (см. README) | — | — |
| TD-11 | Expo web по умолчанию на 8081 конфликтует с Keycloak 8081 | LOW | Запускать web на 8082 или перенести Keycloak |
| TD-12 | C1 приёмки: docker-compose публикует TimescaleDB 5433 (суперпользователь `cadence/cadence`) и Keycloak 8081 (`admin/admin`); у публичного клиента включены direct grants, `webOrigins ["+"]`, localhost redirect; пароль владельца Flyway не защищён guard-ом | MEDIUM, **до любого пилота** | Прод-профиль: порты на 127.0.0.1 или без проброса, недефолтные пароли обеих ролей БД и админа Keycloak, `APP_PERSISTENCE_ALLOW_DEFAULT_DB_PASSWORD=false`, direct grants off, реальные redirect/origins, `trusted-proxies`. Проверка: старт с дефолтными паролями отклоняется; 5432/8081 недоступны снаружи |
| TD-13 | C2: CORS-origins захардкожены на localhost (`SecurityConfig`) — консоль на реальном домене будет заблокирована | MEDIUM | Origins из свойства + тест на неразрешённый origin |
| TD-14 | C3: MASTER читает цены и чужую конфигурацию: `GET /services` (priceMinor), `/specialists/{id}/matrix` и `/schedule` любого мастера, `/rules` — нарушение §5 плана | MEDIUM | Per-resource проверки и тесты для MASTER |
| TD-15 | C4: при merge `trust_state` поглощённого клиента удаляется вместе с ручным override владельца | LOW | Продуктовое решение: переносить ли более строгий override |
| TD-16 | Уборка: fallback nil-UUID тенанта в `JooqAuditLogger` мёртв под RLS; claim `specialist_id` не сверяется с тенантом токена (RLS не даёт межтенантного эффекта) | LOW | Удалить fallback; сверять specialist с тенантом |
