# Отчёт о финальном тестировании — Cadence

Дата прогона: 2026-11-01 (этап 08, прогоны с 16:35 до 17:05 по местному времени)
Основа: коммит `abee64f` (stage 07) плюс незакоммиченные правки этапа 08 (`docs/stages/08-pilot-code-conditions.md`). Изменения не закоммичены и не запушены.
Окружение: macOS (Darwin 24.6.0), Docker Desktop, JDK 21.0.7 (E2E и гейт), Node.js 22.x (frontend). `pmset -g therm` не читается в этой сессии (`Error: No CPU power status with error code 0xe00002bc`), состояние `CPU_Speed_Limit` не подтверждено.
Исполнитель: субагент-тестировщик по задаче T7 этапа 08 (независимая регрессия). Раздел 6.1 и визуальная проверка в разделе 8 — координатор; разделы 6.4 (этап 07) и 6.5 (этап 08) добавлены тестировщиком по фактам логов, журнала этапа и `git diff HEAD`.

**Итог этапа 08 (дописано координатором, заменяет открытые пункты ниже):** оба падения независимого прогона разобраны. `web-host-check` падал из-за самой проверки: `grep -q` под `pipefail` закрывал конвейер (SIGPIPE, код 141), и совпадение читалось как его отсутствие; та же ошибка могла скрыть токен в журнале. После правки `scripts/web-host-check.sh`: PASS, 56 проверок `ok`, 0 FAIL. AI-4 упал по таймауту 30 с один раз из двух полных прогонов при средней нагрузке 198–290; соседний AI-5 в том же прогоне шёл 25 с. Причина установлена косвенно, по времени выполнения, а не по трассе: многоходовый сценарий почти исчерпывал лимит. Файлу `e2e/ai-drafts.spec.ts` дан `test.slow()`; затем `--repeat-each 3`: 12 passed, lint и typecheck exit 0. Полный основной прогон после этой правки не повторялся: она меняет только лимит времени одного файла; последний полный прогон 86 passed.

## 1. Что прогнано и сырые итоги

| # | Шаг | Команда | Результат (сырой итог) |
|---|---|---|---|
| 1 | Backend: Gradle не запускался | Файлы `backend/` старше XML в `backend/modules/app/build/test-results`: самый новый исходник `1793534103`, самый новый XML `1793534596` (`stat -f %m`). По правилу полный build не выполнялся | не применимо |
| 1a | Гейт CI по сохранённым XML | `python3 .github/scripts/junit_summary.py backend --fail-on-skipped --min-tests 400 --require-class 'IT$'` | `Total 1115 \| Failed 0 \| Skipped 0`, `EXIT=0` |
| 2a | Lint | `npm run lint` (`eslint . --max-warnings 0`) | `EXIT=0`, без предупреждений |
| 2b | Self-test линта | `npm run lint:selftest` | `lint self-test ok (29 reported, 24 allowed)`, `EXIT=0` |
| 2c | i18n | `npm run i18n:check` | ru 318/318, ka 65/318, uk 65/318, cs 65/318; `i18n catalogs OK`, `EXIT=0` |
| 2d | Typecheck | `npm run typecheck` (`tsc --noEmit`) | `EXIT=0` |
| 2e | Web export | `npx expo export --platform web --output-dir $TMPDIR/web-export-08` | `EXIT=0`, `Exported: /tmp/claude-501/web-export-08`; каталога `dist` в корне и `frontend/` нет. Sandbox заблокировал телеметрию `cdp.expo.dev:443`, на результат не влияло |
| 3 | Web-host-check | `scripts/web-host-check.sh` (из корня) | Ожидался PASS. Факт в двух прогонах подряд: `web-host-check: FAILED`, `EXIT=1`; `55` проверок `ok`, одна `FAIL the access log holds no request for /b/x`. Разбор в 6.5 |
| 4 | E2E основной, прогон 1 | `npm run e2e` (CI=1, EXPO_NO_TELEMETRY=1) | `1 failed`, `85 passed (6.7m)`, `EXIT=1`. Падение: AI-4 (`e2e/ai-drafts.spec.ts:47`), разбор в 6.5 |
| 4 | E2E основной, прогон 2 | `npm run e2e` | `86 passed (5.8m)`, failed 0, skipped 0, `EXIT=0`, teardown: `no containers, volumes or networks of cadence-e2e left` |
| 5 | E2E OIDC | `npm run e2e:oidc` | `11 passed (46.0s)`, failed 0, skipped 0, `EXIT=0`, teardown: `no containers, volumes or networks of cadence-e2e left` |

Пропущенные тесты (skip): 0 во всех шагах (гейт бэкенда, оба прогона основного E2E, OIDC). Причин пропуска нет, обоснования не требуются.

Падение AI-4 в прогоне 1 не закрыто: прогон 2 зелёный, но причина не установлена, поэтому по критерию «0 падений» этап 08 не закрывается без решения координатора. Playwright в итогах `flaky` не показал; повтор теста внутри прогона не делался.

Нагрузка (`uptime`, 1-минутное среднее): перед web-host-check 5.5 (16:35); перед прогоном 1 отдельно не снималось (первый замер в 16:43, уже во время прогона 1, — 289); во время прогона 1 — 198–290; во время прогона 2 — 77–190; OIDC стартовал при 22.05 (17:01), во время прогона — до 284. Нагрузку создают сами прогоны (Gradle bootRun, expo export, Chromium) и фоновая работа машины; отдельный зависший процесс тестов не найден. Падения не относятся к `CPU_Speed_Limit = 20`, поскольку его прочитать не удалось.

Сырые логи (в `$TMPDIR`, т.е. `/tmp/claude-501`, и в `/private/var/folders/hc/j57r7h1j3pb440wwygrx4jp80000gn/T/`): `s3-web-host-check.log` и `s3b-web-host-check.log` (оба FAILED), `s4-e2e-run1.log`, `s4-e2e-run2.log`, `s5-e2e-oidc.log`, `s2-lint.log`, `s2-lintst.log`, `s2-i18n.log`, `s2-tc.log`, `s2-export.log`. Копии `e2e-results` без `bundle-*`: `<scratchpad>/stage08-run-1/`, `stage08-run-2/`, `stage08-run-oidc/`.

## 2. Флак E9: статус

Тест `calendar: the visit panel covers the feed and takes the width of the content` (`frontend/e2e/e9-narrow-layout.spec.ts:122`, 375 px) ранее падал с `document scrollWidth Received: 617`. По коду причина установлена и закрыта: шрифт иконок Material Symbols — лигатуры веб-шрифта; до его загрузки или при его отсутствии иконки видны как слова и ширина меню выходила за экран. Закрытие: постоянный тест `the menu stays on screen while the icon font is not there` (`e9-narrow-layout.spec.ts:90`), который блокирует запросы к `fonts.gstatic.com` и проверяет, что запросы действительно заблокированы.

Результаты двух финальных прогонов основного E2E этапа 06 (история; итоги этапа 07 — в разделе 1):

| Прогон | Каталог результатов | passed | failed | skipped | `calendar: the visit panel covers the feed` |
|---|---|---:|---:|---:|---|
| 1 | `<scratchpad>/final-run-1/` | 54 | 0 | 0 | passed |
| 2 | `<scratchpad>/final-run-2/` | 54 | 0 | 0 | passed |
| этап 08, прогон 1 | `<scratchpad>/stage08-run-1/` | 85 (+1 failed: AI-4) | 1 | 0 | не относится (E9 прошёл) |
| этап 08, прогон 2 | `<scratchpad>/stage08-run-2/` | 86 | 0 | 0 | не относится (E9 прошёл) |

Строки этапа 08 добавлены для регрессии этапа 08. Падение AI-4 в прогоне 1 не связано с флаком E9 и описано в 6.5.

Тест с заблокированным шрифтом (`e9-narrow-layout.spec.ts:90`) в прогоне 2 passed; в прогоне 1 его результат в логах не проверен отдельно (итог прогона 1: 54 passed, failed 0). Предыдущие прогоны (три прогона в прежней версии отчёта) дали 50/50 passed и не являются основанием для этого раздела. Флак считается закрытым с указанием причины, но это подтверждено двумя прогонами, а не статистикой.

Каталог `<scratchpad>`: `/private/tmp/claude-501/-Users-antonlutsenko-IdeaProjects-AIBusinessManager--claude-worktrees-project-analysis-review-6a40ac/5df28ac6-b488-4c66-80f7-c4aeb5654a8a/scratchpad`.

## 3. E2E: сценарии и результаты

Основной прогон этапа 08: 86 тестов. Прогон 2: 86 passed. Прогон 1: 85 passed и 1 failed (AI-4), всего 86. OIDC: 11 тестов, 11 passed. Число тестов по файлам взято из строк `✓` логов прогона 2 (и OIDC) и совпадает с итогом Playwright.

| Сценарий | Файл | Тестов | Результат | Что проверяет |
|---|---|---:|---|---|
| E1 Клиент бронирует через публичную страницу | `frontend/e2e/e1-client-booking.spec.ts` | 5 | passed | Запись, слоты, согласие, CONFIRMED, владелец видит визит; устаревший ответ слотов; ошибка слотов не равна «нет слотов» и повтор работает (TD-55, тест на 429 и 500); слот, занятый в последний момент; конкурентное изменение |
| E2 Клиент управляет записью по ссылке | `frontend/e2e/e2-manage-link.spec.ts` | 6 | passed | Факты визита без ПДн, перенос, отзыв согласия, отмена, битый токен |
| E3 Владелец: календарь | `frontend/e2e/e3-owner-calendar.spec.ts` | 3 | passed | Неделя/день, фильтр мастера, PENDING → подтверждение, перенос, отмена бизнесом |
| E4 Правила клиента | `frontend/e2e/e4-rules.spec.ts` | 4 | passed | Подтверждение новых клиентов, перенос клиентом выключен, ошибка правил |
| E5 Роль MASTER | `frontend/e2e/e5-master.spec.ts` | 7 | passed | Меню из 3 пунктов, без телефона и денег, прямые URL и API 403 |
| E6 Дашборд владельца | `frontend/e2e/e6-dashboard.spec.ts` | 7 | passed | Карточки без NaN, GEL, «недостаточно данных», копирование ссылки, «Готово»; сигналы по ответу API, без заглушки `METRICS_PENDING` (TD-82) |
| E7 Навигация и каталог | `frontend/e2e/e7-navigation-catalog.spec.ts` | 5 | passed | Маршруты консоли, услуги, мастера, матрица, язык и тема, not-found |
| E8 Вход через OIDC (Keycloak) | `frontend/e2e/oidc/e8-oidc-login.spec.ts` | 9 | passed (прогон OIDC) | Вход владельца и мастера, возврат на запрошенную страницу, выход, ошибка state |
| E9 Вёрстка на 375 px и 1280 px | `frontend/e2e/e9-narrow-layout.spec.ts` | 9 | passed | Нет горизонтального скролла, панель визита на 375 px, меню при заблокированном шрифте, формы каталога при отказе |
| Smoke | `frontend/e2e/smoke.spec.ts` | 1 | passed | Владелец открывает дашборд и видит арендатора |
| AI-1, AI-2 Чат, память, get_schedule | `frontend/e2e/ai-chat.spec.ts` | 3 | passed | Ответ, контекст диалога, расписание с реальными именами; AI-1b: диалог стёрт до отправки, текст не уходит, новый диалог |
| AI-3…AI-5 Черновики | `frontend/e2e/ai-drafts.spec.ts` | 4 | passed в прогоне 2; failed в прогоне 1 (AI-4, таймаут 30 с) | Ничего не меняется до Confirm; отклонение карточки (AI-4, разбор в 6.5); перенос и отмена; DRAFT_STALE |
| AI-6, AI-7 Бюджет и настройки | `frontend/e2e/ai-budget-settings.spec.ts` | 2 | passed | 429 с баннером при исчерпанном бюджете; переключатель передачи заметок с предупреждением |
| AI-8 Доступ MASTER к AI | `frontend/e2e/ai-access.spec.ts` | 4 | passed | 403 на AI-эндпоинты, лимит 4001 символ, 404 на неизвестный диалог, экран только для владельца |
| E10 Редактирование каталога из консоли (этап 07) | `frontend/e2e/e10-catalog-editing.spec.ts` | 15 | passed | Пустой каталог → услуга, мастер, матрица, график → запись клиента; новая цена меняет только новые визиты; MASTER без правок и денег, API 403; неподгрузившаяся неделя без формы сохранения; отказ чтения не стирает список; перенос по ссылке у второго арендатора; деактивация мастера и услуги; привязка входа не стирается; валюта по знакам; русский текст форм |
| Unit-ввод каталога (этап 07) | `frontend/e2e/unit-catalog-input.spec.ts` | 8 | passed | Перевод цены в минорные единицы по знакам валюты; без округления; пустая и отрицательная цена с кодом; время `ЧЧ:ММ`; дата `ГГГГ-ММ-ДД` (Playwright без браузера, чистые функции) |
| E11 Анонимизация клиента по запросу (этап 08) | `frontend/e2e/e11-client-anonymize.spec.ts` | 3 | passed | Владелец анонимизирует клиента один раз с подтверждением; при будущем визите отказ 409; имя и телефон пропадают; MASTER не видит действия, API даёт 403; карточка идёт по состоянию профиля, а не по имени (человек «anonymized», поглощённый профиль) |
| E11 OIDC: запись каталога по настоящему токену Keycloak (этап 08) | `frontend/e2e/oidc/e11-oidc-catalog-write.spec.ts` | 2 | passed (прогон OIDC) | Владелец с токеном Keycloak создаёт услугу и меняет цену, бэкенд записывает subject токена; MASTER с токеном получает 403 на запись и не видит кнопку добавления (закрывает пункт 6 критериев этапа 08) |

Сумма основного прогона: E1 5 + E2 6 + E3 3 + E4 4 + E5 7 + E6 7 + E7 5 + E9 9 + smoke 1 + AI 13 + E10 15 + E11 3 + unit 8 = 86. Прирост к версии этапа 07 (78): E11 3, E1 +2, E6 +2, AI +1. Цифры по файлам взяты из логов прогона 2.

## 4. Backend: состояние и покрытие

В этой итерации Gradle не запускался: XML в `backend/modules/app/build/test-results` новее исходников `backend/` (см. раздел 1, шаг 1). Гейт `junit_summary.py --fail-on-skipped --min-tests 400 --require-class 'IT$'` выполнен по этим результатам и вернул exit 0. Результаты относятся к сборке, на которой они получены; свежий `build --no-build-cache --rerun-tasks` для этапа 08 не выполнен.

Итог гейта по модулям (сырой вывод):

| Module | Tests | Failed | Skipped | Time (s) |
|---|---:|---:|---:|---:|
| ai | 330 | 0 | 0 | 26.6 |
| app | 411 | 0 | 0 | 142.1 |
| booking-engine | 116 | 0 | 0 | 10.8 |
| catalog | 12 | 0 | 0 | 0.3 |
| crm | 158 | 0 | 0 | 7.3 |
| growth | 48 | 0 | 0 | 3.5 |
| platform | 34 | 0 | 0 | 9.3 |
| retention | 6 | 0 | 0 | 0.2 |
| **Total** | **1115** | **0** | **0** | **200.1** |

Итого 1115 тестов при ожидаемых 1115: 0 падений, 0 skip. Прирост к прежней версии (1075): +40. Гейт `--min-tests 400` и `--require-class 'IT$'` пройдены.

Покрытие в процентах не измеряется: в сборке нет JaCoCo (TD-70).

Изменённые и добавленные в этапе 06 тестовые классы: `AccessLinkIT`, `MetricsJournalReplayIT`, `HiddenClientResponsesTest`, `HiddenClientVisitTest`, `MetricsCalculatorTest`, `MetricsGoldenDatasetTest`.

Классы этапа 07: `CatalogEditingIT` (23 теста с `@Test`, `backend/modules/app/src/test/java/com/cadence/app/CatalogEditingIT.java`) и `CatalogServiceImplTest` (2 теста, `backend/modules/catalog/src/test/java/com/cadence/catalog/internal/CatalogServiceImplTest.java`). Оба входят в зелёный прогон. Модульный счётчик `catalog` = 12 тестов.

Классы этапа 08 (новые файлы в `git status`): `ClientAnonymizationIT` (`backend/modules/app/src/test/java/com/cadence/app/ClientAnonymizationIT.java`, сканирует текстовые колонки всех таблиц), `ConsoleSlotsIT`, `DashboardSignalsIT`, `ApiExceptionHandlerSqlTest`. Изменённые в этапе 08 тесты в гейте зелёные. Покрытие в процентах по-прежнему не измеряется (TD-70).

## 5. E2E: покрытие по экранам

| Экран / маршрут | Где проверяется |
|---|---|
| `/book/[slug]` (публичная запись) | E1, E4, E9 (375 px) |
| `/b/[id]` (управление записью по ссылке) | E2, E4, E9 |
| `/dashboard` (владелец) | E6, smoke, E7, E9 |
| `/calendar` (владелец и мастер) | E3, E4, E9 (панель визита, 375 и 1280 px) |
| `/clients`, `/clients/[id]` | E5 (MASTER без телефона и счётчиков), E11 (анонимизация из карточки) |
| `/services` | E5 (прямой URL MASTER), E7, OIDC E11 (запись услуги с токеном Keycloak) |
| `/specialists`, `/specialists/[id]` | E5 (прямой URL), E7 (матрица) |
| `/rules` | E4, E5 (MASTER получает отказ) |
| `/ai`, AI-экраны | AI-1…AI-8, E5 |
| Вход и `/auth` (OIDC) | E8 |
| Not-found | E7 |
| Навигация консоли, язык, тема | E7, E9 (меню при заблокированном шрифте) |

## 6. Найденные дефекты и статусы

Статусы сверены с `git diff HEAD` и `docs/tech-debt.md` (открытые пункты на этап 06: TD-55, TD-57…TD-67, TD-64 позже закрыт, см. 6.4; TD-56 закрыт с остатком, TD-63 закрыт частично). Там, где пункт не подтверждён кодом или тестом, это указано явно.

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
| TD-64 | `/b/{id}` без `slug` запрашивал слоты переноса у демо-арендатора. Закрыт в этапе 07: это был блокер пилота, а не риск второго арендатора | закрыт |
| TD-65 | Правило `timeFormat24h` не влияет на показ времени | LOW |
| TD-66 | В OIDC блок «Debug / locale» скрыт: ручной пересчёт метрик и `ka`/`uk`/`cs` недоступны; каталоги `ka`/`uk`/`cs` заполнены на 65 из 217 ключей | LOW |
| TD-67 | Шрифт иконок и веб-шрифты грузятся с Google Fonts: внешний запрос раскрывает IP клиента; без сети иконки видны как слова | MEDIUM |

### 6.3 Расхождения с прошлой версией отчёта и с кодом

- R3. Прежняя версия отчёта помечала D5 (время) как открытое. По коду исправлено (секунды убраны, пояс арендатора применяется); открыт только TD-65 (24-часовой формат).
- R4. Прежняя версия называла D2 (кнопки статусов), D3 (шрифт) открытыми: оба исправлены, см. 6.1.
- R5. Счётчики E2E в прежней версии (50 тестов, E1 = 1, E3 = 2, E9 = 7) не совпадают с логами (54 теста, E1 = 3, E3 = 3, E9 = 8). Отчёт обновлён по логам.
- R6. Прежняя таблица i18n давала 213 ключей; текущие каталоги содержат 217 (`en`, `ru`).

### 6.4 Этап 07 (редактирование каталога, TD-68)

Добавлено в этапе: E10 `frontend/e2e/e10-catalog-editing.spec.ts` (15 тестов), unit-ввод `frontend/e2e/unit-catalog-input.spec.ts` (8), `CatalogEditingIT` (23 теста с `@Test`), `CatalogServiceImplTest` (2), новые файлы `CatalogValidation.java`, `CatalogRequests.java`, `frontend/src/utils/catalogErrors.ts`, `frontend/src/utils/schedule.ts`, `frontend/components/catalog/`. В E9 добавлен тест формы каталога при отказе на 375 px (`e9-narrow-layout.spec.ts:126`). Числа прогонов этапа 07: JUnit-гейт 1075 (было 1048), E2E 78 (было 54), OIDC 9 (было 8).

Статусы ниже сверены с тестами из логов прогонов (`passed` в обоих прогонах или в гейте) и с `git diff HEAD`. Красный прогон до каждой правки в данных, доступных тестировщику, не зафиксирован: подтверждение здесь означает, что тест существует и зелёный на текущем коде.

| Исправленный дефект | Подтверждение | Статус |
|---|---|---|
| Стирание графика при сбое загрузки | E10 `a week that did not load has no form to save over...` (`e10-catalog-editing.spec.ts:364`), `a read that fails keeps the list and the open form` (:388) | Подтверждено: passed в прогонах 1 и 2 |
| Отсутствие аудита каталога | IT `catalogChangesAreAuditedWithBeforeAfterAndActor` (`CatalogEditingIT.java:601`); код: `CatalogServiceImpl` пишет `service.update` / `service.create` через `auditLogger` (строка 85) | Подтверждено: IT зелёный в гейте. E2E аудит не проверяет |
| Доступ деактивированного мастера | IT `aDeactivatedMasterIsLockedOutUntilReactivated` (:494), `aVisitOfADeactivatedMasterCanOnlyBeCancelled` (:470); E10 :445 и :499; код: `SPECIALIST_INACTIVE` в `TenantContextFilter.java` | Подтверждено: зелёные |
| Строгий разбор тела каталога | IT `aBodyThatCannotBeReadIsRefusedWithACode` (:104), `aMissingOrFractionalValueIsRefusedInsteadOfBecomingZeroOrFalse` (:116) | Подтверждено: зелёные |
| Разбор цены без округления | unit `a price the currency cannot hold is refused, never rounded` (`unit-catalog-input.spec.ts:28`), `an empty, negative or unreadable price is an error with its code` (:61) | Подтверждено: unit зелёный. Проверяется на уровне функций, не через API |
| 500 на чужом id | IT `anotherTenantsIdsBehaveLikeUnknownIds` (:337), `anIdNobodyKnowsDoesNotCreateARow` (:374); E10 `invalid input over the API gets a 4xx with a code, never a 500` (:564) | Подтверждено: зелёные |
| Стирание привязки входа | IT `savingASpecialistWithoutTheLinkKeepsIt` (:386), `onlyTheOwnerRemovesASignInLinkAndDoingItTwiceIsFine` (:516); E10 `editing a master keeps the sign-in link the form does not show` (:636) | Подтверждено: зелёные |
| Перенос по ссылке у не демо-арендатора (TD-64) | E10 `a client of another tenant moves the visit by the very link the booking gave` (:423); `tech-debt.md`: TD-64 закрыт | Подтверждено: passed. Параметр `&slug=` тест проверяет на старых ссылках, он не читается |
| Запасной `LUMEN_SLUG` | `grep LUMEN_SLUG` по `frontend/app`, `src`, `components`, `e2e` в рабочем дереве: совпадений нет (в HEAD были `src/api/client.ts:493`, `calendar.tsx`, `b/[id].tsx`) | Подтверждено кодом. Отдельного теста нет |
| Запасной `GEL` | В `git diff HEAD` удалены две строки `const currency = me?.currencyCode ?? 'GEL'`. Остаток в рабочем дереве: `frontend/app/b/[id].tsx:174` `formatMoney(visit.priceSnapshot, visit.currencyCode ?? 'GEL', ...)` | **Исправлено частично.** Остаток подтверждён grep-ом. Отдельного теста на запасной GEL нет |

Открытое из этапа 07 (`docs/tech-debt.md`): TD-69 (нет unit-тестов `datetime.ts`, переход на летнее время и Hermes не проверены), TD-70 (покрытие не измеряется, JaCoCo нет), TD-71 (панель визита не показывает снимок цены), TD-72 (`publicVisible=false` не закрывает `/slots` и `POST /bookings`), TD-73 (`weekStartsOn` не влияет на график, даты и время вводятся текстом), TD-74 (lost update в каталоге: нет версии строки). TD-68 закрыт этапом 07.

Расхождение с журналом этапа: `docs/stages/07-catalog-editing.md`, запись журнала, указывает 1071 тест и E2E 74. Фактически в этом прогоне 1075 и 78. Журнал тестировщиком не менялся.

### 6.5 Этап 08 (условия пилота на стороне кода)

Источники: `docs/stages/08-pilot-code-conditions.md` (критерии и журнал), `docs/tech-debt.md`, `git diff HEAD` (64 файла, +1252/−168, плюс новые файлы), логи прогонов раздела 1. Критерии этапа (по журналу) и факты прогонов сведены ниже. Статусы взяты из журнала и `tech-debt.md`; там, где тест этапа 08 не падал в прогонах, это указано явно.

**Что добавлено и изменено в этапе**

- Статический хост (TD-57): сервис `web` в `docker-compose.prod.yml`, `docker/web` (nginx без root, read-only, `127.0.0.1:8082`), правила как в `frontend/scripts/e2e-serve.mjs`; проверка `scripts/web-host-check.sh` и job `web` в CI. Проверка на настоящем nginx в этом прогоне: см. «Найденное» ниже, результат FAILED.
- Анонимизация (TD-46): `POST /api/app/clients/{id}/anonymize` (OWNER, `ClientAnonymizationService`), экран карточки с необратимым подтверждением, `ClientAnonymizationIT`, E11 (3 теста). Диалоги AI, где клиент или поглощённый им профиль есть в `ai_pseudonym`, удаляются вместе с сообщениями, черновиками и журналами. Остатки: TD-47, TD-79, TD-80, TD-81. Статус по TD-79: «Промпты у провайдера обязательно указать в уведомлении о приватности»; со `stub` этого остатка нет.
- Слоты консоли (TD-76): `GET /api/app/slots` под авторизацией, мастеру только свои (403 на чужого), без публичного лимита; `ConsoleSlotsIT`, E3. Страница `/b/{id}` по-прежнему в публичном маршруте.
- Диапазон слотов: `INVALID_SLOT_RANGE` (400), если `to` раньше `from` или диапазон больше 62 дней (`openapi.yaml`); ревью нашло неограниченный диапазон и это исправлено.
- `/book` при 429 и прочей ошибке слотов показывает отдельный текст и кнопку повтора, а не «нет слотов» (TD-55); E1 проверяет ошибку, повтор и 429/500.
- Цена в панели визита (TD-71): владельцу показывается снапшот, мастеру API отдаёт `null`, панель его не показывает (E3, E5).
- Блок «Today» с завтрашними визитами: найден ревью, исправлен (журнал этапа).
- Заглушка `METRICS_PENDING` (TD-82): удалена; E6 ожидания берутся из ответа `/api/app/dashboard`; `GrowthServiceImpl.signals` без заглушки, пустой список показывается как «нет сигналов». Тест `MetricsCalculatorTest` проверяет текст пояснения выручки.
- Запись каталога через настоящий токен Keycloak (T5, пункт 6 критериев): `frontend/e2e/oidc/e11-oidc-catalog-write.spec.ts`, 2 теста, в прогоне OIDC passed.
- Прерванный ход ассистента: если диалог стёрт во время хода, ход получает 409 `AI_CONVERSATION_ERASED`, текст провайдеру после стирания не уходит (`AiAssistantServiceImpl`); ревью нашло запись текста заметок в журналы AI и идущий ход при удалении, оба исправлены по журналу.

**Исправленные дефекты этапа 08 и подтверждение в прогонах этого раздела**

| Дефект | Подтверждение в прогонах 08 |
|---|---|
| TD-57 статический хост без rewrite | Код и правила есть; `web-host-check` в обоих прогонах упал на одной проверке (см. ниже), поэтому подтверждение неполное |
| TD-46 анонимизация без маршрута | E11 (3 passed в прогоне 2, 3 в прогоне 1 не падали), `ClientAnonymizationIT` в гейте (зелёный) |
| TD-76 слоты консоли под публичным лимитом | `ConsoleSlotsIT` и E3 в гейте и E2E, passed |
| TD-55 429 как «нет слотов» | E1, passed в обоих прогонах |
| TD-71 цена в панели | E3, E5, passed |
| TD-82 заглушка `METRICS_PENDING` | E6, passed в обоих прогонах |
| Каталог только в dev-headers (критерий 6) | OIDC E11 (2 passed) |
| Диапазон слотов | `INVALID_SLOT_RANGE` описан в openapi; отдельного E2E на 62+ дней в этом отчёте не выделено |

Красный прогон до каждого исправления тестировщиком не наблюдался и не фиксировался: подтверждение означает, что тест существует и зелёный на текущем коде.

**Найденное тестировщиком в этом прогоне (падения, сырой текст)**

1. Web-host-check, два прогона подряд (`scripts/web-host-check.sh`), `web-host-check: FAILED`, `EXIT=1`. Единственная падающая проверка: `FAIL  the access log holds no request for /b/x`. Остальные 55 проверок `ok`, в том числе маршруты, 404, 400 на обход путей, заголовки CSP, отсутствие `localhost` в бандле, отсутствие токена в логе.
   - Что установлено: `/b/x?token=y` отвечает 200 в контейнере; отдельная проба (образ собран вручную, контейнер удалён) показала в `docker logs`: `172.17.0.1 [01/Nov/2026:12:39:17 +0000] "GET /b/x" 200 20004 "curl/8.7.1"`; `grep '/b/x'` по логу в пробе дал 1 строку. Значит, nginx пишет этот запрос в stdout, как и должно быть (образ `nginxinc/nginx-unprivileged` делает `access.log -> /dev/stdout`).
   - Вероятная причина (по коду скрипта и модели в shell, в контейнере напрямую не наблюдалась): `set -o pipefail` и `docker logs ... | grep -q '/b/x'` в `scripts/web-host-check.sh:168`. При совпадении в начале и большом выводе после него `grep -q` выходит раньше, `docker logs` получает SIGPIPE, конвейер возвращает 141, и `if` считает проверку проваленной. Модель: `{ echo "GET /b/x"; seq 1 200000; } | grep -q "/b/x"` при `pipefail` даёт `rc=141`; при совпадении в конце — `pass`. В скрипте проверка `token=` такого эффекта не даёт: совпадений нет, `grep -q` не выходит раньше.
   - Расхождение: журнал этапа записывает `web-host-check 55, 0 failed`. Тестировщик получил 55 `ok` и 1 `FAIL` в обоих прогонах (`s3-web-host-check.log`, `s3b-web-host-check.log`).
   - Скрипт и `docker/web` не менялись тестировщиком. Исправление за координатором.
2. E2E, прогон 1: AI-4 (`frontend/e2e/ai-drafts.spec.ts:47`, «rejecting the second card rejects only it and changes nothing in the calendar»). Сырой текст: `Test timeout of 30000ms exceeded.` и `Error: apiRequestContext.get: Target page, context or browser has been closed` на `frontend/e2e/support/ai.ts:104` (вызов из `calendarIds`, строка 65 теста). Тест занял 35.9 с. Все шаги до финальной проверки API прошли. Прогон 2: AI-4 passed. Причина не установлена: похоже на таймаут теста в период нагрузки до 290, но это предположение, не подтверждённый факт. Скриншот падения: `<scratchpad>/stage08-run-1/test-results/ai-drafts-AI-4-rejecting-t-eded5-ges-nothing-in-the-calendar-chromium/test-failed-1.png`; трасса `…/trace.zip` в той же папке.

**Открытое (`docs/tech-debt.md`, этап 08)**

- TD-47 (LOW): окно `refFor` — ход диалога, в котором клиента ещё нет в псевдонимах, может записать его имя после анонимизации; запись журналов до 30 дней.
- TD-78 (LOW): CSP сервиса `web` держит `'unsafe-inline'` в `script-src` и `style-src`; вход и выход через Keycloak под CSP `web` проверить на первом деплое.
- TD-79 (LOW): анонимизация не трогает чужие имена вне `ai_pseudonym`, промпты у настоящего провайдера остаются по его сроку; до включения настоящего провайдера указать в уведомлении о приватности вместе с DPA.
- TD-80 (LOW): аудит `public.rate_limited` хранит маскированный телефон и маскированный IP, анонимизация его не трогает.
- TD-81 (LOW): возможна транзакция, прерванная PostgreSQL (40P01) при точном совпадении времени; данные не страдают, ответ `CONCURRENT_RETRY`.
- TD-57 остаток: проверка `/book/<slug>` и `/b/<id>?token=` на реальном адресе при первом деплое (шаг оператора, TD-21).

Расхождения с журналом этапа (`docs/stages/08-pilot-code-conditions.md`, запись 2026-11-01): журнал пишет `web-host-check 55, 0 failed`; в этом прогоне `FAILED`. Журнал не менялся.

## 7. Что не проверено

- Вход через Keycloak под CSP на настоящем nginx (сервис `web`): не подтверждено. В OIDC-прогонах этапа 08 фронтенд работает на локальном `e2e-serve`, а не на образе `web`; TD-78.
- `docker compose up` стека с сервисом `web`: не запускался. Образ `web` собирал только `web-host-check.sh`, и он упал (см. 6.5).
- Job `e2e` и job `web` в GitHub Actions: не запускались, проверяются после push. Локально выполнены два прогона `npm run e2e` и один `npm run e2e:oidc`.
- Адрес за TLS и реальный адрес прода: не проверялись (TD-57, TD-21, шаг оператора).
- Живой DeepSeek: не проверялся (шаг пользователя, внешний субпроцессор, деньги).
- Native-сборка (iOS, Android): не делалась.
- Независимое ревью этапа 08 и приёмка: проводятся координатором и архитектором, в этом отчёте не отражены.
- Этап 06: последний круг правок без отдельного ревью. Этап 07: последний круг правок (M1, m1–m7) без отдельного ревью.
- Формы каталога в режиме OIDC: E10 идёт в dev-headers; запись услуги по токену покрыта одним сценарием OIDC E11.
- Покрытие кода в процентах: не измеряется, JaCoCo в сборке нет.

## 8. Скриншоты

- Этап 08, прогон 1, 90 записей в каталоге: `<scratchpad>/stage08-run-1/screenshots/`; снимок падения AI-4: `<scratchpad>/stage08-run-1/test-results/ai-drafts-AI-4-rejecting-t-eded5-ges-nothing-in-the-calendar-chromium/test-failed-1.png`
- Этап 08, прогон 2, 92 записи в каталоге: `<scratchpad>/stage08-run-2/screenshots/`
- Этап 08, OIDC, 8 записей: `<scratchpad>/stage08-run-oidc/screenshots/`
- Скриншоты этапа 08 тестировщиком не открывались: визуальный просмотр за координатором.
- Этап 07, прогон 1, 86 файлов: `<scratchpad>/stage07-run-1/screenshots/`
- Этап 07, прогон 2, 86 файлов: `<scratchpad>/stage07-run-2/screenshots/`
- Этап 07, OIDC, 6 файлов: `<scratchpad>/stage07-run-oidc/screenshots/`
- Этап 06, 62 + 62 + 6 файлов: `<scratchpad>/final-run-1/`, `final-run-2/`, `final-run-oidc/`

`<scratchpad>` = `/private/tmp/claude-501/-Users-antonlutsenko-IdeaProjects-AIBusinessManager--claude-worktrees-project-analysis-review-6a40ac/5df28ac6-b488-4c66-80f7-c4aeb5654a8a/scratchpad`.

Исходный каталог в дереве (игнорируется git): `frontend/e2e-results/screenshots/`, перезаписывается каждым прогоном. Каталог `<scratchpad>` временный: скриншоты воспроизводятся командой `npm run e2e`.

Визуальная проверка координатора: просмотрены дашборд (светлая, тёмная, RU; 1280 и 375 px), календарь с панелью, матрица, правила, услуги, карточка клиента, `/book` и экран успеха, `/b/{id}`, экраны AI, дашборд мастера. Часть экранов просмотрена на версиях до последних правок; `dashboard-owner-ru-375` и `book-success-375` просмотрены на финальном прогоне. Остальные скриншоты (всего 62 + 6) не открывались. Найденное на этих экранах исправлено, см. 6.1. Открыто: серверные тексты на английском на RU-дашборде (TD-58), правило 24h не влияет на показ времени (TD-65).

Этап 07: координатор просмотрел `catalog-specialist-detail` (1280, 375), `catalog-specialist-detail-master-375`, `catalog-service-form-error-375`, `catalog-services-owner-1280`, `catalog-master-inactive-375`, `catalog-service-form-ru-1280`, `catalog-matrix-form-375`, `catalog-profile-not-loaded-1280`, `catalog-specialist-form-375`; архитектор — 9 файлов `catalog-*`. Остальные не открывались. По просмотру добавлены имя мастера на странице и заголовок шапки у MASTER.

## 9. Окружение и уборка Docker

Раннер `frontend/scripts/e2e.mjs` создаёт проект `cadence-e2e` и удаляет его сам. Во всех трёх прогонах teardown вывел `no containers, volumes or networks of cadence-e2e left`. Проверка после прогонов: `docker ps -a --filter name=cadence-e2e`, `docker volume ls --filter name=cadence-e2e`, `docker network ls --filter name=cadence-e2e` — 0 объектов.

Что сессия удалила вручную: ничего. `docker rm`, `docker rmi`, `docker volume rm`, `prune` (включая `system`, `image -a`, `volume`, `builder`) не запускались.

Что осталось и не трогалось:
- 14 запущенных контейнеров `docker.elastic.co/mcp/elasticsearch` (чужие).
- Остановленные `cadence-keycloak` (Exited 7 дней назад) и `cadence-timescaledb` (Exited 15 часов назад): dev-объекты, не удалялись.
- Контейнеры `cip-trkd-pg` и `cip-trkd-redis` были в списке в начале сессии, при финальной проверке отсутствуют. Сессия их не удаляла; причина не установлена (то же наблюдалось в прежней версии отчёта). Не восстанавливались: это не объекты задачи.
- Тома dev-БД и чужие тома оставлены.

Этап 08 (T7, тестировщик):
- Удалено по имени: образ `cadence-web-probe-08:local` (проба для разбора падения web-host-check, `docker rmi`, подтверждено `Deleted`); контейнер `cadence-web-probe-08` (`rm -f -v`); образ `cadence-web-check:local` удаляет `web-host-check.sh` (без `--keep-image`), после двух прогонов его нет; контейнеры `cadence-web-check-<pid>` ушли с `--rm`.
- Проект `cadence-e2e`: раннер удалил сам. После трёх прогонов (основной 1, основной 2, OIDC) `docker ps -a`, `docker volume ls` и `docker network ls` с фильтром `cadence-e2e` дают 0 объектов.
- Скачано и оставлено как базовый образ проекта: `nginxinc/nginx-unprivileged:1.28.0-alpine` (`FROM` в `docker/web/Dockerfile`, загружен пробой). Образ `node:22.16.0-bookworm-slim` (стадия сборки `web`) в `docker images` не виден; откуда взята сборка, не проверял.
- Не трогалось: контейнеры `docker.elastic.co/mcp/elasticsearch` (чужие); `cadence-keycloak` (Exited, dev) и `cadence-timescaledb` (Exited, dev); `cip-withdrawn-pg`, `cip-withdrawn-redis`, `cip-wd-pg`, `cip-wd-redis` (Up, не мои, появились во время сессии). Тома не удалялись, по `cadence` томов нет.
- Команды `docker system prune`, `docker image prune -a`, `docker volume prune`, `docker builder prune` не запускались.
