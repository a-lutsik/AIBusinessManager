# Отчёт о финальном тестировании — Cadence

Дата прогона: 2026-10-31
Основа: коммит `c542d6d` (stage 05) плюс незакоммиченные правки этапа 06 (`docs/stages/06-final-testing.md`). Изменения не закоммичены и не запушены.
Окружение: macOS (Darwin 24.6.0), Docker Desktop, JDK 21.0.7 (Gradle и E2E), Node.js 22.23.2 (frontend).
Исполнитель: субагент-тестировщик по задаче T7, раздел 6.1 и визуальная проверка в разделе 8 — координатор.

## 1. Что прогнано и сырые итоги

| # | Шаг | Команда | Результат (сырой итог) |
|---|---|---|---|
| 1 | Backend build без кеша | не запускался: Java-код не менялся с последнего полного прогона | см. раздел 4: результат 1048 тестов остаётся в силе |
| 1a | Гейт CI (сверка по XML) | `python3` суммирование `TEST-*.xml` по `backend/**/build/test-results` | 87 файлов, tests 1048, failures 0, errors 0, skipped 0 |
| 2a | Lint | `npm run lint` (`eslint . --max-warnings 0`) | exit 0, без предупреждений |
| 2b | Self-test линта | `npm run lint:selftest` | `lint self-test ok (29 reported, 24 allowed)`, exit 0 |
| 2c | i18n | `npm run i18n:check` | en 217/217, ru 217/217, ka 65/217, uk 65/217, cs 65/217; `i18n catalogs OK`, exit 0 |
| 2d | Typecheck | `npm run typecheck` (`tsc --noEmit`) | exit 0 |
| 2e | Web export | `npx expo export --platform web --output-dir $TMPDIR/web-export-final` | exit 0, `Exported: /tmp/claude-501/web-export-final`; в дереве каталога `dist` не создано. В выводе sandbox отказы на `cdp.expo.dev:443` (телеметрия Expo), на результат не влияют |
| 3 | E2E основной, прогон 1 | `npm run e2e` | `54 passed (2.1m)`, failed 0, skipped 0, `EXIT=0`, teardown: `no containers, volumes or networks of cadence-e2e left` |
| 3 | E2E основной, прогон 2 | `npm run e2e` | `54 passed (2.0m)`, failed 0, skipped 0, `EXIT=0`, teardown: `no containers, volumes or networks of cadence-e2e left` |
| 4 | E2E OIDC | `npm run e2e:oidc` | `8 passed (23.7s)`, failed 0, skipped 0, `EXIT=0`, teardown: `no containers, volumes or networks of cadence-e2e left` |

Пропущенные тесты (skip): 0 во всех шагах (XML-отчёты бэкенда, два прогона основного E2E, OIDC). Причин пропуска нет, обоснования не требуются. Flaky: 0.

Сырые логи (в scratchpad): `e2e-run1.log`, `e2e-run2.log`, `e2e-oidc.log`, `npm-lint.log`, `npm-lint_selftest.log`, `npm-i18n_check.log`, `npm-typecheck.log`, `expo-export.log`.

## 2. Флак E9: статус

Тест `calendar: the visit panel covers the feed and takes the width of the content` (`frontend/e2e/e9-narrow-layout.spec.ts:122`, 375 px) ранее падал с `document scrollWidth Received: 617`. По коду причина установлена и закрыта: шрифт иконок Material Symbols — лигатуры веб-шрифта; до его загрузки или при его отсутствии иконки видны как слова и ширина меню выходила за экран. Закрытие: постоянный тест `the menu stays on screen while the icon font is not there` (`e9-narrow-layout.spec.ts:90`), который блокирует запросы к `fonts.gstatic.com` и проверяет, что запросы действительно заблокированы.

Результаты двух финальных прогонов основного E2E:

| Прогон | Каталог результатов | passed | failed | skipped | `calendar: the visit panel covers the feed` |
|---|---|---:|---:|---:|---|
| 1 | `<scratchpad>/final-run-1/` | 54 | 0 | 0 | passed |
| 2 | `<scratchpad>/final-run-2/` | 54 | 0 | 0 | passed |

Тест с заблокированным шрифтом (`e9-narrow-layout.spec.ts:90`) в прогоне 2 passed; в прогоне 1 его результат в логах не проверен отдельно (итог прогона 1: 54 passed, failed 0). Предыдущие прогоны (три прогона в прежней версии отчёта) дали 50/50 passed и не являются основанием для этого раздела. Флак считается закрытым с указанием причины, но это подтверждено двумя прогонами, а не статистикой.

Каталог `<scratchpad>`: `/private/tmp/claude-501/-Users-antonlutsenko-IdeaProjects-AIBusinessManager--claude-worktrees-project-analysis-review-6a40ac/5df28ac6-b488-4c66-80f7-c4aeb5654a8a/scratchpad`.

## 3. E2E: сценарии и результаты

Основной прогон: 54 теста, 54 passed (два прогона). OIDC: 8 тестов, 8 passed. Число тестов по файлам взято из строк `✓` логов прогона 2 и сходится с итогом Playwright.

| Сценарий | Файл | Тестов | Результат | Что проверяет |
|---|---|---:|---|---|
| E1 Клиент бронирует через публичную страницу | `frontend/e2e/e1-client-booking.spec.ts` | 3 | passed | Запись, слоты, согласие, CONFIRMED, владелец видит визит |
| E2 Клиент управляет записью по ссылке | `frontend/e2e/e2-manage-link.spec.ts` | 6 | passed | Факты визита без ПДн, перенос, отзыв согласия, отмена, битый токен |
| E3 Владелец: календарь | `frontend/e2e/e3-owner-calendar.spec.ts` | 3 | passed | Неделя/день, фильтр мастера, PENDING → подтверждение, перенос, отмена бизнесом |
| E4 Правила клиента | `frontend/e2e/e4-rules.spec.ts` | 4 | passed | Подтверждение новых клиентов, перенос клиентом выключен, ошибка правил |
| E5 Роль MASTER | `frontend/e2e/e5-master.spec.ts` | 7 | passed | Меню из 3 пунктов, без телефона и денег, прямые URL и API 403 |
| E6 Дашборд владельца | `frontend/e2e/e6-dashboard.spec.ts` | 5 | passed | Карточки без NaN, GEL, «недостаточно данных», копирование ссылки, «Готово» |
| E7 Навигация и каталог | `frontend/e2e/e7-navigation-catalog.spec.ts` | 5 | passed | Маршруты консоли, услуги, мастера, матрица, язык и тема, not-found |
| E8 Вход через OIDC (Keycloak) | `frontend/e2e/oidc/e8-oidc-login.spec.ts` | 8 | passed (прогон OIDC) | Вход владельца и мастера, возврат на запрошенную страницу, выход, ошибка state |
| E9 Вёрстка на 375 px и 1280 px | `frontend/e2e/e9-narrow-layout.spec.ts` | 8 | passed | Нет горизонтального скролла, панель визита на 375 px, меню при заблокированном шрифте |
| Smoke | `frontend/e2e/smoke.spec.ts` | 1 | passed | Владелец открывает дашборд и видит арендатора |
| AI-1, AI-2 Чат, память, get_schedule | `frontend/e2e/ai-chat.spec.ts` | 2 | passed | Ответ, контекст диалога, расписание с реальными именами |
| AI-3…AI-5 Черновики | `frontend/e2e/ai-drafts.spec.ts` | 4 | passed | Ничего не меняется до Confirm; отклонение карточки; перенос и отмена; DRAFT_STALE |
| AI-6, AI-7 Бюджет и настройки | `frontend/e2e/ai-budget-settings.spec.ts` | 2 | passed | 429 с баннером при исчерпанном бюджете; переключатель передачи заметок с предупреждением |
| AI-8 Доступ MASTER к AI | `frontend/e2e/ai-access.spec.ts` | 4 | passed | 403 на AI-эндпоинты, лимит 4001 символ, 404 на неизвестный диалог, экран только для владельца |

Сумма основного прогона: E1 3 + E2 6 + E3 3 + E4 4 + E5 7 + E6 5 + E7 5 + E9 8 + smoke 1 + AI 12 = 54.

## 4. Backend: состояние и покрытие

Gradle в этой итерации не запускался. Основание: сравнение времён файлов. Самый новый файл результатов `backend/modules/app/build/test-results/test/TEST-com.cadence.app.AiAssistantIT.xml` имеет mtime 2026-10-31 17:40:09; файлов `*.java`, `*.yml`, `*.yaml`, `*.gradle*`, `*.sql` в `backend/` новее него нет. Поэтому результат полного прогона (1048 тестов, 0 падений, 0 skip) остаётся в силе.

Сверка по XML-отчётам (87 файлов в `backend/**/build/test-results`): tests 1048, failures 0, errors 0, skipped 0. Сходится с последним гейтом `junit_summary.py` (`Total 1048 | Failed 0 | Skipped 0`).

Покрытие в процентах не измеряется: в сборке нет JaCoCo. Таблица покрытия по модулям из прежней версии отчёта в этой итерации не пересчитывалась (число тестовых классов и тестов по модулям не перепроверено).

Изменённые в этапе 06 тестовые классы: `AccessLinkIT`, `MetricsJournalReplayIT`, `HiddenClientResponsesTest`, `HiddenClientVisitTest`, `MetricsCalculatorTest`, `MetricsGoldenDatasetTest`.

## 5. E2E: покрытие по экранам

| Экран / маршрут | Где проверяется |
|---|---|
| `/book/[slug]` (публичная запись) | E1, E4, E9 (375 px) |
| `/b/[id]` (управление записью по ссылке) | E2, E4, E9 |
| `/dashboard` (владелец) | E6, smoke, E7, E9 |
| `/calendar` (владелец и мастер) | E3, E4, E9 (панель визита, 375 и 1280 px) |
| `/clients`, `/clients/[id]` | E5 (MASTER без телефона и счётчиков) |
| `/services` | E5 (прямой URL MASTER), E7 |
| `/specialists`, `/specialists/[id]` | E5 (прямой URL), E7 (матрица) |
| `/rules` | E4, E5 (MASTER получает отказ) |
| `/ai`, AI-экраны | AI-1…AI-8, E5 |
| Вход и `/auth` (OIDC) | E8 |
| Not-found | E7 |
| Навигация консоли, язык, тема | E7, E9 (меню при заблокированном шрифте) |

## 6. Найденные дефекты и статусы

Статусы сверены с `git diff HEAD` и `docs/tech-debt.md` (открытые пункты: TD-55, TD-57…TD-67; TD-56 закрыт с остатком, TD-63 закрыт частично). Там, где пункт не подтверждён кодом или тестом, это указано явно.

### 6.1 Исправлено

| Дефект | Подтверждение |
|---|---|
| Блокер: на экране входа URL подменялся на `/ai`, поэтому после Keycloak пользователь всегда попадал туда, а не на запрошенную страницу (E8). `DEFAULT_RETURN_PATH` был верным, неверным был считанный путь: layout группы не монтировал навигатор | `frontend/app/(console)/_layout.tsx:39` монтирует `Navigator` всегда; до правки E8: 1 passed, 3 failed, проба `replaceState` показывала `/ai`; после: 8 passed |
| Слоты при выключенном переносе (E4) | Поле `clientRescheduleAllowed`, `AccessLinkIT.publicVisitTellsWhetherTheClientMayReschedule`, E4; red наблюдался |
| Форма `/rules` у MASTER (E5) | `rules.tsx`: при 403 сообщение об отказе без формы; E5; red наблюдался |
| Шапка, календарь и матрица на 375 px (E9) | E9; red наблюдался (484, 617 и 851 px) |
| Пояснение Revenue per hour (E6) | `MetricsCalculatorTest.revenuePerHourExplanationIsReadable`, E6; red наблюдался |
| Сверка `id` с токеном шла после коммита: операция применялась к визиту токена и возвращала 403 (`AccessLinkIT`) | `requireToken(id, token)` до мутации; `AccessLinkIT.tokenOfAnotherVisitChangesNothing`, `foreignTokenAndUnknownIdAnswerAlikeAndAuditNothing`; red наблюдался для первого |
| Ошибки под панелью (E9) | E2E с `route.fulfill` 409 при 375 px; red наблюдался |
| Сырой `{manageLink}` (E6) | `frontend/app/(console)/dashboard.tsx:174` заменяет плейсхолдер на `t('task.linkOnCopy')` |
| Кнопки с названиями статусов вместо действий (E3) | Ключи `visit.action.*`; E3 проверяет Confirm и Cancel; подписи Mark completed и Mark no-show не нажимаются ни одним тестом |
| Засечный шрифт на RU (E6) | `frontend/src/theme/tokens.ts:11`: web-стек `"Plus Jakarta Sans", Inter, "Noto Sans Georgian", system-ui, sans-serif` без serif-семейств |
| Названия метрик и заголовки задач на RU (TD-58: остаток открыт) | Названия метрик и `NEW_BOOKING` переведены на клиенте; остаток — в TD-58 |
| Время с секундами и без пояса арендатора (E2, E3) | `frontend/src/utils/datetime.ts`: без секунд (`DATE_TIME_OPTIONS`), принимает IANA-пояс арендатора. Остаток по 24-часовому формату — TD-65 (открыт) |
| Период календаря считался от полуночи браузера, при браузере в другом поясе пропадали утренние визиты (E3) | `calendar.tsx:54–55`, `startOfZoneDay` от пояса арендатора; E3 с `America/Los_Angeles`; red наблюдался |
| 100+ слотов на `/book` (E1) | Выбор дня, слоты выбранного дня; скриншот `book-slot-375` просмотрен; TD-56 закрыт с остатком (диалоги переноса) |
| Устаревшие ответы слотов и 409 на `/book` (E1) | Отмена устаревшего ответа, сброс слота, повторный запрос; E1 с задержанным ответом и `route` 409; red наблюдался |
| Относительная ссылка на экране успеха (E1) | Полная ссылка, кнопки открытия и копирования; E1 проверяет абсолютный `href` и открытие того же визита; скриншот `book-success-375` просмотрен |
| Блок «Debug / locale» в OIDC | `frontend/app/(console)/_layout.tsx:163`: блок показывается только при `mode === 'dev-headers'`; в OIDC скрыт |
| Меню шире экрана без шрифта иконок (E9, флак) | `e9-narrow-layout.spec.ts:90`, постоянный тест с заблокированным шрифтом; красный прогон 617 px в прежней версии |
| Деньги для валют не с двумя знаками (`money.ts`) | `frontend/src/utils/money.ts`: знаки берутся из `Intl.NumberFormat` с валютой |

### 6.2 Открыто (TD)

| TD | Что | Приоритет |
|---|---|---|
| TD-55 | `/book`: ошибка загрузки слотов (в том числе 429) показывается как «нет слотов»: `.catch(() => setSlots([]))` в `frontend/app/book/[slug].tsx:82–83` подтверждён в коде | MEDIUM |
| TD-57 | Статический хост прода обязан переписывать динамические маршруты; конфигурации в репозитории нет | HIGH для выкладки веба |
| TD-58 | Серверные пояснения метрик и формулы на английском; заголовок «мастер записал по телефону», сигналы роста на сервере | LOW |
| TD-59 | Поле `clientRescheduleAllowed` в `openapi.yaml` описано текстом, без проверки схемы полей | LOW |
| TD-60 | E2E «недостаточно данных» на дашборде через подмену ответа API | LOW |
| TD-61 | Оболочка консоли на 375 px занимает около 260 px высоты | LOW |
| TD-62 | `Navigator` из `expo-router` в группе `(console)` проверен только на web | MEDIUM |
| TD-63 | `SystemPromptFactory.price` в AI на неизвестной валюте даёт ×10 (XXX, XAU) | LOW |
| TD-64 | `/b/{id}` без `slug` запрашивает слоты переноса у демо-арендатора | MEDIUM при втором арендаторе |
| TD-65 | Правило `timeFormat24h` не влияет на показ времени | LOW |
| TD-66 | В OIDC блок «Debug / locale» скрыт: ручной пересчёт метрик и `ka`/`uk`/`cs` недоступны; каталоги `ka`/`uk`/`cs` заполнены на 65 из 217 ключей | LOW |
| TD-67 | Шрифт иконок и веб-шрифты грузятся с Google Fonts: внешний запрос раскрывает IP клиента; без сети иконки видны как слова | MEDIUM |

### 6.3 Расхождения с прошлой версией отчёта и с кодом

- R3. Прежняя версия отчёта помечала D5 (время) как открытое. По коду исправлено (секунды убраны, пояс арендатора применяется); открыт только TD-65 (24-часовой формат).
- R4. Прежняя версия называла D2 (кнопки статусов), D3 (шрифт) открытыми: оба исправлены, см. 6.1.
- R5. Счётчики E2E в прежней версии (50 тестов, E1 = 1, E3 = 2, E9 = 7) не совпадают с логами (54 теста, E1 = 3, E3 = 3, E9 = 8). Отчёт обновлён по логам.
- R6. Прежняя таблица i18n давала 213 ключей; текущие каталоги содержат 217 (`en`, `ru`).

## 7. Что не проверено

- Живой DeepSeek: шаг пользователя (деньги, внешний субпроцессор). Не проверялся.
- Native-сборка (iOS, Android): не делалась.
- Job `e2e` в GitHub Actions: проверяется только после push. Локально выполнены два прогона `npm run e2e` и `npm run e2e:oidc`.
- Прод за TLS и статический хост: не проверялись (TD-57).
- Последний круг правок (по третьему ревью) без отдельного ревью; три предыдущих круга независимого ревью выполнены.
- Покрытие кода в процентах: не измеряется, JaCoCo в сборке нет.

## 8. Скриншоты

- Прогон 1, 62 файла: `<scratchpad>/final-run-1/screenshots/`
- Прогон 2, 62 файла: `<scratchpad>/final-run-2/screenshots/`
- OIDC, 6 файлов: `<scratchpad>/final-run-oidc/screenshots/`

`<scratchpad>` = `/private/tmp/claude-501/-Users-antonlutsenko-IdeaProjects-AIBusinessManager--claude-worktrees-project-analysis-review-6a40ac/5df28ac6-b488-4c66-80f7-c4aeb5654a8a/scratchpad`.

Исходный каталог в дереве (игнорируется git): `frontend/e2e-results/screenshots/`, перезаписывается каждым прогоном. Каталог `<scratchpad>` временный: скриншоты воспроизводятся командой `npm run e2e`.

Визуальная проверка координатора: просмотрены дашборд (светлая, тёмная, RU; 1280 и 375 px), календарь с панелью, матрица, правила, услуги, карточка клиента, `/book` и экран успеха, `/b/{id}`, экраны AI, дашборд мастера. Часть экранов просмотрена на версиях до последних правок; `dashboard-owner-ru-375` и `book-success-375` просмотрены на финальном прогоне. Остальные скриншоты (всего 62 + 6) не открывались. Найденное на этих экранах исправлено, см. 6.1. Открыто: серверные тексты на английском на RU-дашборде (TD-58), правило 24h не влияет на показ времени (TD-65).

## 9. Окружение и уборка Docker

Раннер `frontend/scripts/e2e.mjs` создаёт проект `cadence-e2e` и удаляет его сам. Во всех трёх прогонах teardown вывел `no containers, volumes or networks of cadence-e2e left`. Проверка после прогонов: `docker ps -a --filter name=cadence-e2e`, `docker volume ls --filter name=cadence-e2e`, `docker network ls --filter name=cadence-e2e` — 0 объектов.

Что сессия удалила вручную: ничего. `docker rm`, `docker rmi`, `docker volume rm`, `prune` (включая `system`, `image -a`, `volume`, `builder`) не запускались.

Что осталось и не трогалось:
- 14 запущенных контейнеров `docker.elastic.co/mcp/elasticsearch` (чужие).
- Остановленные `cadence-keycloak` (Exited 7 дней назад) и `cadence-timescaledb` (Exited 15 часов назад): dev-объекты, не удалялись.
- Контейнеры `cip-trkd-pg` и `cip-trkd-redis` были в списке в начале сессии, при финальной проверке отсутствуют. Сессия их не удаляла; причина не установлена (то же наблюдалось в прежней версии отчёта). Не восстанавливались: это не объекты задачи.
- Тома dev-БД и чужие тома оставлены.
