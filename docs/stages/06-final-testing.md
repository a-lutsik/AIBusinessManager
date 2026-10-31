# Этап 06 — финальное тестирование перед пилотом

Цель: критические пользовательские пути проверены в браузере через Playwright на изолированном окружении, результаты зафиксированы в `docs/TEST_REPORT.md` и `docs/ACCEPTANCE.md`.
Критерий готовности этапа:
1. все сценарии ниже (E1–E8 и AI-1…AI-8) проходят на чистом окружении одной командой, 0 failed, 0 skip без обоснования;
2. прогон не затрагивает dev-БД, dev-бэкенд и существующие тома Docker; после прогона проект `cadence-e2e` удалён;
3. скриншоты основных экранов (1280 и 375 px) просмотрены координатором, дефекты исправлены или записаны в `docs/tech-debt.md`;
4. полная регрессия зелёная: backend и фронтенд как в CI;
5. `docs/TEST_REPORT.md` и `docs/ACCEPTANCE.md` написаны, приёмка архитектора.

Живая проверка DeepSeek остаётся шагом пользователя (деньги, внешний субпроцессор). Риск этапа: MEDIUM.

## Решения архитектора по окружению
- Основной прогон: бандл `EXPO_PUBLIC_AUTH_MODE=dev-headers`, `EXPO_PUBLIC_API_URL=http://localhost:18080`, бэкенд на хосте с `SPRING_PROFILES_ACTIVE=dev-headers`, роль через `addInitScript` в localStorage (`cadence.role`, `cadence.specialistId`).
- OIDC (E8) автоматизирован отдельным конфигом `e2e:oidc`: второй бандл на порту 19006 (`EXPO_PUBLIC_AUTH_MODE=oidc`, `EXPO_PUBLIC_OIDC_ISSUER=http://localhost:18081/realms/cadence`), второй процесс бэкенда без dev-headers с `APP_SECURITY_ISSUER_URI=http://localhost:18081/realms/cadence`, dev-реалм.
- Изоляция: `docker-compose.e2e.yml`, проект `-p cadence-e2e`, без `container_name`; TimescaleDB на 15433 с `tmpfs` вместо тома; Keycloak на 18081 под compose-профилем `oidc`; те же образы, что в dev compose.
- Бэкенд не собирается в образ: `./gradlew :modules:app:bootRun` на хосте, `SERVER_PORT=18080`, `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15433/cadence`. Сид: `DemoDataSeeder` на пустой БД.
- Лимиты публичного API подняты в окружении бэкенда: `APP_RATE_LIMIT_SLOTS_PER_MINUTE_PER_IP=1000`, `APP_RATE_LIMIT_BOOKINGS_PER_HOUR_PER_TENANT=1000`, `APP_RATE_LIMIT_BOOKINGS_PER_HOUR_PER_IP=1000`, `APP_RATE_LIMIT_BOOKINGS_PER_DAY_PER_PHONE=100`.
- Фронтенд: `npx expo export --platform web` + `npx expo serve`. `workers: 1`.
- Сценарии создают свои визиты через `POST /api/public/tenants/lumen-studio/bookings` с уникальным телефоном `+9955550xxxxx`; сидовые визиты только читают.
- Global setup падает, если API URL не 18080 или 18080 не отвечает. Global teardown и обработка падения: `docker compose -p cadence-e2e down -v --remove-orphans`.

## Сценарии E (утверждены архитектором)
- E1 Клиент, `/book/lumen-studio`: Haircut → мастера без Elena → Nino → слоты завтра..+7, все в будущем → страна GE предвыбрана, имя, телефон, согласие → успех CONFIRMED с `managePath`; `.ics` через `request` → `text/calendar`; RU меняет заголовки. Затем OWNER: задача «новая запись» на дашборде, визит в календаре (неделя), клиент находится поиском в `/clients`.
- E2 Клиент, `/b/{id}?token=`: ссылка из E1 или перехват ответа `POST …/access-link`; видны #ref, услуга, время, имя, цена; нет телефона и истории; перенос → слоты → CONFIRMED, время меняется; отмена → CANCELLED_BY_CLIENT, кнопки исчезают; отзыв согласия → подтверждение; битый token → текст ошибки без данных визита.
- E3 OWNER, календарь: неделя/день; фильтр Nino убирает строки Manicure/Pedicure/Brow; свой будущий PENDING → в панели телефон, Trust, только Confirm/Cancel → Confirm → CONFIRMED в панели и списке; перенос → диалог слотов → другой день; отмена бизнесом → CANCELLED_BY_BUSINESS, кнопки переноса нет.
- E4 OWNER, правила → клиент: «подтверждение новых клиентов», Save → «сохранено», reload сохраняет; новый телефон через `/book` → успех PENDING, в календаре PENDING; выключить «перенос клиентом» → на `/b/{id}` читаемая ошибка вместо слотов; `afterEach` возвращает правила через `PUT /api/app/rules`.
- E5 MASTER (`cadence.role=MASTER`, `cadence.specialistId=00000000-0000-4000-8000-000000000011`): меню только Today/Calendar/Clients, заголовок «Today», на дашборде нет метрик и задач; панель визита без телефона; `/clients` и `/clients/{id}` без телефона и счётчиков, причины Trust переведены из кодов; текст страниц без `+995` и сумм GEL; прямые URL `/services`, `/rules`, `/specialists/{nino}` → API 403 (`waitForResponse`), цен на экране нет.
- E6 OWNER, дашборд: 6 карточек метрик без `NaN`/`undefined`, денежная в GEL, при малой выборке «недостаточно данных», окно дат; «Скопировать сообщение» (разрешение clipboard) → «скопировано» и `POST …/access-link`; «Готово» убирает задачу; сигналы или пустое состояние.
- E7 OWNER, навигация и каталог: каждый маршрут консоли → заголовок без текста ошибки; `/services` 8 карточек с ценами; `/specialists` 3 → матрица Nino: Color с переопределением 135 мин, да/нет по сиду; EN↔RU и тёмная тема переживают reload; неизвестный URL → not-found → ссылка на дашборд.
- E8 OIDC: owner@lumen.studio → redirect на Keycloak → `/auth` → дашборд с именем арендатора; reload без второго входа; Sign out → экран входа, `/dashboard` снова просит пароль; nino@lumen.studio → меню мастера, `/api/app/me` role=MASTER; `/auth?code=x&state=bad` → карточка ошибки с «повторить».

Сценарии AI-1…AI-8: раздел «Сценарии финального тестирования» в `docs/stages/05-ai-manager.md`. AI-6: `monthly_token_budget = 0` SQL-фикстурой через `docker exec` в контейнер проекта `cadence-e2e`, сброс в `NULL` в `afterEach`.

Скриншоты (1280 и 375 px, светлая; дашборд ещё тёмная и RU): дашборд OWNER и MASTER; календарь с панелью и диалог слотов; `/clients` и карточка клиента (OWNER и MASTER); услуги; мастера; матрица Nino; правила; `/book` на шагах услуга/слот/контакт и оба экрана успеха; `/b/{id}` активный, отменённый, битый токен; экран входа и ошибка `/auth`; not-found; экраны AI.

## Задачи
- [x] T1 окружение: `docker-compose.e2e.yml`, Playwright, конфиги, global setup/teardown, команды `e2e` и `e2e:oidc`, дымовой сценарий — исполнитель: worker — проверил Opus: ☑ (`npm run e2e` зелёный дважды, 76 и 83 с; уборка проверена при упавшем сценарии; принятые отклонения: свой статический сервер `e2e-serve.mjs`, потому что `expo serve` отдаёт 404 на динамические маршруты; уборка в раннере; `timezoneId: Asia/Tbilisi`)
- [x] T2 сценарии E1–E7 и скриншоты — исполнитель: worker (после T1) — проверил Opus: ☑ (первый прогон 22 passed, 4 failed: 3 дефекта приложения и ошибка ожидания в файле этапа; после T5 зелёные; добавлен E9)
- [x] T3 сценарии AI-1…AI-8 — исполнитель: worker (после T2) — проверил Opus: ☑ (12 passed дважды; ссылки читаются из `ai_pseudonym`, потому что на экране они восстановлены; `CANCELLED_BY_CLIENT` через AI в E2E не покрыт, покрыт `AiAssistantIT`; совместный прогон с E — в T7)
- [x] T4 сценарий E8 (OIDC) — исполнитель: worker (после T3) — проверил Opus: ☑ (первый прогон 1 passed, 3 failed: возврат после входа всегда на `/ai`; после T5 8 passed)
- [x] T5 исправление найденных дефектов и записи TD — исполнитель: complex_coder — проверил Opus: ☑ (три круга независимого ревью; исправленное перечислено в `docs/TEST_REPORT.md`, раздел 6.1; последний круг правок UI отдельного ревью не имел, прочитан архитектором; ожидание E5 исправлено: для MASTER `/services` и своя матрица отвечают 200 с обнулёнными деньгами, 403 у `/rules` и чужой матрицы; открытое в TD-55, TD-57…TD-70)
- [x] T6 job E2E в CI (не обязательная проверка в branch protection) и раздел README — исполнитель: worker — проверил Opus: ☑ (YAML разобран; реальный запуск job возможен только после push)
- [x] T7 полная регрессия и `docs/TEST_REPORT.md` — исполнитель: tester — проверил Opus: ☑ (backend 1048, 0 failed, 0 skipped; E2E 54 дважды, OIDC 8; статусы дефектов в отчёте исправлены координатором)
- [x] T8 `docs/ACCEPTANCE.md` и приёмка приложения — исполнитель: architect — проверил Opus: ☑

Статусы: todo → in_progress → review → done | returned.

## Проверка этапа
- [x] Все задачи ✔ (Opus)
- [x] Регрессия зелёная (tester)
- [x] Скриншоты просмотрены (Opus): частично, основные экраны, два на финальном прогоне (`docs/TEST_REPORT.md`, раздел 8)
- [x] Приёмка архитектором: ☑ принято — 2026-10-31; приложение в объёме пилота принято условно, условия в `docs/ACCEPTANCE.md`

## Журнал
- 2026-10-31: финальный прогон: backend 1048, E2E 54 дважды, OIDC 8, 0 failed, 0 skipped. Причина флака E9 установлена (шрифт иконок, red 617 px), закрыта постоянным тестом. Этап принят, не закоммичен.
- 2026-10-31: контрольный прогон перед последними правками: backend 1047, E2E 47 и 8, 0 failed, 0 skipped. Просмотр скриншотов координатором: сырой `{manageLink}` в задачах, кнопки с названиями статусов, засечный шрифт на RU, непереведённые метрики, время с секундами, 100+ слотов на `/book`, проверка видимости «Debug / locale» — возвращены в T5. Повторное ревью: 5 minor, возвращены в T5. Решение координатора: группировка слотов чинится сейчас, это главный путь клиента.
- 2026-10-31: T5 — E + AI 45 passed, OIDC 7 passed, backend 1042 теста, 0 failed, 0 skipped. Блокер входа: layout группы `(console)` не монтировал навигатор, URL пересчитывался на первый экран группы. Осталось: независимое ревью T5.
- 2026-10-31: этап открыт, перечень сценариев и окружение утверждены архитектором. `(tabs)` — legacy-редиректы на `/dashboard`; вид мастера — группа `(console)` с фильтром меню по роли.
