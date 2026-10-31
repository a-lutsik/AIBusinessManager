# Этап 05 — AI-менеджер (фаза 3)

Цель: ассистент отвечает цифрами из Metrics Core и предлагает действия черновиками, которые владелец подтверждает, а Booking Engine валидирует; ПДн к провайдеру не уходят.
Критерий готовности этапа (IT со скриптуемым stub):
1. ассистент отвечает цифрами из `get_metrics`, равными `/api/app/metrics`;
2. создаются `draft_booking`/`draft_reschedule`/`draft_cancel`, прошедшие предварительную проверку; до подтверждения `appointment` не меняется;
3. подтверждение применяет действие через `BookingService` с его валидацией; отказ движка → `FAILED` с кодом; повторное подтверждение → 409;
4. в `ai_prompt_log`/`ai_tool_call`/`ai_message` нет ни одного имени или телефона клиента или мастера (скан фикстурой + `PhoneShape`); `GET /ai/conversations/{id}` возвращает восстановленные имена из карты в БД;
5. превышение бюджета → 429 `AI_BUDGET_EXCEEDED`, `/calendar` и `confirm` работают;
6. заметки по умолчанию не уходят; при включённом тумблере уходят маскированными;
7. живая проверка DeepSeek — шаг пользователя по README (не гейт).

Риск: HIGH (ПДн к субпроцессору, запись в календарь по черновику). Дизайн: `docs/adr/0002-ai-tool-loop-pseudonymization-drafts.md`.

## Задачи

- [x] A0 архитектура AI-менеджера — исполнитель: architect — решение в ADR 0002
- [x] T1 V15 + `schema.sql` + платформенные stores (`AiConversationStore`, `AiPseudonymStore`, `AiDraftStore` с `requireForUpdate` и условными апдейтами, `AiUsageStore` с атомарным upsert, `AiSettingsStore`, `AiLogStore`), `TenantInfo`, ArchUnit-правило владения `Ai*` — исполнитель: complex_coder — риск HIGH — тесты: `AiStoreIT` под `cadence_app`, `RlsIsolationIT`, ретенция в `timescaledb_information.jobs` — проверил Opus: ☑ (AiStoreIT 21, RlsIsolationIT 12, ArchUnit 16; полный build 552 теста, 0 failed, 0 skipped; red-check: без `FOR UPDATE` и без условия `PENDING` падают 4 теста; отклонения от ADR: `entity_id NOT NULL`, составной FK `(tenant_id, conversation_id)` против привязки строк к чужому диалогу, `ai_draft` каскадно удаляется с диалогом, CHECK-ограничения; T2 перенесён в эту копию, ai-тесты и ArchUnit зелёные вместе) — ревью: 0 blocker, 1 major (взаимная блокировка: `FOR UPDATE` на `ai_conversation` против `FOR KEY SHARE` от FK потомков → `FOR NO KEY UPDATE`), 4 minor (усечение `tool_name`/`error_code`; нет теста составного FK для `ai_message`/`ai_pseudonym`/`ai_draft`; бэкфилл V15 при общем `conversation_id` у двух арендаторов; порядок `append` при параллельных вызовах) — исправления: ☑ код внесён (red→green по каждому), полный прогон 729 тестов, 0 failed, 0 skipped, гейт CI ✔; повторная проверка: 5 находок закрыты, deadlock-цепочек нет; правило для T4: не брать блокировку диалога вместе с `requireForUpdate(draft)` и upsert `ai_usage_month` в разном порядке; ☑
- [x] T2 порт `LlmRequest/LlmResponse`, DeepSeek tool calling + usage + finish_reason, скриптуемый stub с директивным режимом, `validate()` для thinking — исполнитель: complex_coder — риск MEDIUM — тесты: `DeepSeekLlmProviderTest` (14), `StubLlmProviderTest` (11), 0 failed — проверил Opus: ☑ (сериализация ролей и tool_calls по документации DeepSeek; формат `tool_calls` в отправляемых assistant-сообщениях документацией не описан — по формату OpenAI, проверяется живой проверкой; `tool_choice` отправляется только вместе с `tools`, поэтому финальный вызов T4 должен передавать tools с NONE; временная константа 1024 вместо `app.ai.max-completion-tokens` — заменить в T4)
- [x] T3 псевдонимизация: `PseudonymRegistry`, `FreeTextMasker` + стоп-слова (5 локалей), `OwnerMessageMasker`, `Restorer`; удалить старый `Pseudonymizer` — исполнитель: complex_coder (после T1) — риск HIGH — тесты: `PseudonymRegistryTest`, `FreeTextMaskerTest`, `OwnerMessageMaskerTest`, `RestorerTest`, проверка «ни одна строка ПДн фикстуры не попадает в выход» — проверил Opus: ☑ (53 теста privacy + 25 llm, 0 failed; red: заглушки без маскирования → 31 из 50 падают; найдены и исправлены переполнение стека regex на длинных цифровых строках и утечка телефонов с точками; полный build 626 тестов, 0 failed, 0 skipped; решения: сообщение владельца — regex без стоп-слов, имена — совпадение целым словом, индексы — только в контексте, `@handle` → `[url]`; изменение поведения: неизвестный `conversationId` → 404) — ревью: возвращено (1 blocker — телефоны с другими разделителями; 5 major — щит дат, лимит 100 строк, адреса, стоп-слова, квадратичный regex; 10 minor); доработка: 175 тестов модуля ai, 0 failed, red 174/73; повторное ревью: B1, M1, M2, M5 закрыты, блокеров нет; последний круг: пакетный поиск имён одним запросом, адреса, общая страховка телефонов, стоп-слова: ☑ код внесён (`CrmService.findByNameWords`, один запрос на сообщение; полный build 810 тестов, 0 failed, 0 skipped); ревью последнего круга — вместе с T4: ☑; зависимость от локали БД (при `C` кириллица не сопоставится) — в техдолг; ADR §3.2–3.3 обновить в T7
- [x] T4 оркестратор: `ToolRegistry` + 7 инструментов, цикл с лимитами, `TokenBudget`, `SystemPromptFactory`, журналы, `ai_message`, `DraftPayloads`/preflight/`DraftApplier`, сервис настроек, аудит — исполнитель: complex_coder (после T1–T3) — риск HIGH — тесты: unit с фейками (лимит итераций, бюджет посреди хода, `UNKNOWN_REF`, `DRAFT_LIMIT`, отказ preflight), `ToolResultsLeakTest`, `DraftApplierTest` — проверил Opus: ☑ — ревью: ☑ (несколько кругов, см. журнал)
- [x] T5a контроллер, `openapi.yaml`, `NOT_MONEY`, web-тест 429 и 403 для MASTER на всех `/ai/**` — исполнитель: worker (после T4) — тесты: `OpenApiContractTest`, `MoneyFieldClassifierTest`, `AppApiSecurityWebTest` — проверил Opus: ☑
- [x] T5b `AiAssistantIT` по сценариям (a)–(h) — исполнитель: test_writer (после T5a) — проверил Opus: ☑
- [x] T6 фронтенд: карточки трёх типов, статусы, баннер бюджета, панель настроек, `ApiError.code`, без `optionalRequest` для AI, i18n en/ru — исполнитель: worker (после T5a, параллельно T5b) — тесты: lint, lint:selftest, i18n:check, typecheck, web export, скриншоты — проверил Opus: ☑
- [x] T7 `AiRetentionJob`, конфигурация `app.ai.*`, README: «Live check DeepSeek», обновление «What is implemented»/«Gaps» — исполнитель: worker (после T4) — тесты: IT ретенции — проверил Opus: ☑

Статусы: todo → in_progress → review → done | returned.
Порядок: T1 ∥ T2 → T3 → T4 → T5a → T5b ∥ T6 → T7. T2 работает в отдельном worktree (два Gradle-процесса в одном checkout портят результаты тестов).

### Сценарии T5b
(a) метрики → слоты → `draft_booking` → текст; `drafts[0].status=PENDING`, в календаре визита нет; confirm → визит в `/calendar`, аудит `ai.draft.create`/`ai.draft.confirm`/`appointment.create` по `entity_id`; второй confirm → 409 `DRAFT_NOT_PENDING`.
(b) `expires_at` в прошлом → 409 `DRAFT_EXPIRED`.
(c) `draft_reschedule`, визит перенесён через UI → confirm → 409 `DRAFT_STALE`, статус `FAILED`.
(d) `draft_booking`, слот занят между созданием и confirm → 409 `SLOT_OCCUPIED`, `FAILED`.
(e) бюджет: использовано 1 000 001 → следующий chat 429, `/calendar` 200, confirm открытого черновика 200.
(f) приватность: журналы промптов, инструментов и сообщения без имён клиентов и мастеров сида и без телефонов (`PhoneShape`); `GET /ai/conversations/{id}` содержит имена; арендатор B → 404 на диалог A, пустой список черновиков.
(g) заметки: «аллергия на краску, тел. +995…» → по умолчанию `[withheld…]`; после `PUT /ai/settings {notesToLlm:true}` → `[sensitive]`/`[phone]`, исходного текста нет.
(h) сообщение длиннее 4000 символов → 400 `AI_MESSAGE_TOO_LONG`.

## Разделение файлов
- T1: `backend/modules/platform/**` (миграция V15, `db/jooq/schema.sql`, `platform/ai/**`, `platform/tenant/**`), ArchUnit-тест, `AiStoreIT`.
- T2: `backend/modules/ai/src/main/java/com/cadence/ai/internal/llm/**`, `StubLlmProvider`, `DeepSeekLlmProvider`, их тесты.
- T3: `backend/modules/ai/src/main/java/com/cadence/ai/internal/privacy/**`, `ai/src/main/resources/ai-masking/**`.
- T4: остальной `backend/modules/ai/**`.
- T5a: `backend/modules/app/src/main/java/com/cadence/app/web/**`, `openapi.yaml`, `MoneyFieldClassifierTest`.
- T5b: новый `AiAssistantIT`.
- T6: `frontend/**`.
- T7: `backend/modules/app/src/main/java/com/cadence/app/batch/**`, `application.yml`, README.

## Проверка этапа
- [x] Все задачи ✔ (Opus)
- [x] Регрессия зелёная (как в CI, отдельный прогон тестировщика): `CI=true ./gradlew build --no-build-cache --rerun-tasks` — 1040 тестов, 0 failed, 0 skipped, гейт `--fail-on-skipped --min-tests 400 --require-class 'IT$'` exit 0; фронтенд lint, lint:selftest (29/24), i18n:check (en/ru 196/196), typecheck, web export ✔
- [x] Независимое ревью T1, T3, T4 (HIGH): последняя правка падежных форм (точки разреза, окончания) проверена архитектором при приёмке, отдельного повторного ревью не было
- [x] TD-36…TD-54 в `docs/tech-debt.md`
- [x] Приёмка архитектором: ☑ принято — 2026-10-31, по всем семи пунктам критерия. В финальное тестирование: перечень сценариев Playwright утверждён архитектором (ниже); живая проверка DeepSeek — шаг пользователя

### Сценарии финального тестирования (утверждены архитектором)
1. Ход чата со stub (`>>text …`): ответ, id диалога в localStorage, после перезагрузки история с настоящими именами; на экране нет `CLIENT_n`/`MASTER_n`/UUID.
2. `>>tool get_schedule {…}` по дню с визитом сида: ответ с реальным именем клиента.
3. `draft_booking`: карточка PENDING, календарь без изменений → Confirm → CONFIRMED, визит на экране календаря, кнопок нет.
4. Reject второй карточки → REJECTED, календарь без изменений.
5. `draft_reschedule` и `draft_cancel`; устаревание: перенос через календарь, затем Confirm → FAILED с переводом `DRAFT_STALE`.
6. Бюджет: низкий `monthly_token_budget` (SQL-фикстура) → 429, баннер; confirm и календарь работают.
7. Тумблер заметок сохраняется после перезагрузки, предупреждение видно.
8. API: MASTER → 403 на всех семи `/api/app/ai/**`; 4001 символ → 400 `AI_MESSAGE_TOO_LONG`; случайный id → 404 и восстановление UI; `/ai` под MASTER — экран «только владелец». Скриншоты, в том числе 375 px.
Оговорка: директивный режим stub через HTTP с маскером не прогонялся. Токены в `>>tool` становятся `[ref]`, если ещё неизвестны диалогу, а сообщение маскируется раньше регистрации `MASTER_n`/`SERVICE_n` в этом ходу: нужен разогревочный ход (`>>text hi`), номера ссылок читать из ответов.

## Журнал
- 2026-10-26: этап открыт. Решения пользователя: заметки в AI по умолчанию выключены (тумблер, маскирование); месячный бюджет 1 млн токенов, `AI_BUDGET_EXCEEDED`; только stub в тестах. Решения координатора по умолчанию: бюджет задаёт оператор, мастера токенизируются как `MASTER_n`. ADR 0002 принят.
- 2026-10-31: диск Docker VM был заполнен (1,1 ГБ); с разрешения пользователя удалены кеш сборки и образы timescaledb 2.17.2/2.20.2-pg17 и oryx builder, тома не тронуты, свободно 6,5 ГБ. Решение координатора по стоп-словам: маскирование от клаузы со стоп-словом до конца предложения.
- 2026-10-31: T4 — полный build 870 тестов, 0 failed, 0 skipped; red мутациями 59/17. T5a — веб-тесты 20, 0 failed. T6 — lint, selftest, i18n:check, typecheck, web export ✔, визуально на моке, с реальным бэкендом не проверено. Ревью T4: 0 blocker, 3 major (M1 имена в audit_log и анонимизация не доходит до AI-данных; M2 подтверждение черновика на анонимизированном или поглощённом клиенте; M3 падежи имён), 8 minor. Решение пользователя по M3: сравнивать имена по основе слова.
- 2026-10-31: T5b — `AiAssistantIT` 15 тестов зелёные, 5 мутаций пойманы. Правки T4 по ревью: 953 теста, 0 failed, 0 skipped. T7 — `AiRetentionJobIT` 2 теста, README, ADR §13, TD-36..52. Повторное ревью T4: авторизация, данные, транзакции ✔, новых major нет; последний круг: короткие имена, окончания падежей вместо любых хвостов, `reason` в виде ссылок. Пользователь принял остаток по чередованиям (`Павел/Павла`) при выборе сравнения по основе.
- 2026-10-31: последний круг T4: 1011 тестов, 0 failed, 0 skipped, гейт CI ✔. Проверка экрана AI с настоящим бэкендом (dev-headers, stub): настройки, `get_schedule`, карточка `draft_cancel` с реальными именами и восстановленной причиной, подтверждение → `CONFIRMED`, `appliedEntityId` записан; скан локальной базы: имён в `ai_prompt_log`, `ai_tool_call`, `ai_message` нет. Карточки `draft_booking` и `draft_reschedule` с настоящим API на экране не проверялись (покрыты `AiAssistantIT` и моком); на ширине 375 px панель настроек занимает половину экрана (TD-51).
- 2026-10-31: последняя правка падежных форм, контрольный прогон тестировщика 1040 тестов, 0 failed, 0 skipped. Приёмка архитектором: принято. Этап закрыт, не закоммичен.
