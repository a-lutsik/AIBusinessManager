# ADR 0002 — AI-менеджер: цикл инструментов, псевдонимизация в БД, черновики с подтверждением

Статус: принято 2026-10-26 (архитектор). Решения пользователя: заметки по умолчанию не передаются в LLM (тумблер владельца, при включении — маскирование); месячный бюджет токенов на арендатора, по умолчанию 1 000 000, при превышении `AI_BUDGET_EXCEEDED`; в тестах и E2E только stub-провайдер, живая проверка DeepSeek — шаг пользователя по README.
Решения координатора по умолчанию (сообщены пользователю): бюджет настраивает оператор, владелец видит его только для чтения; мастера токенизируются как `MASTER_n` (имена сотрудников — тоже ПДн).
Контекст: PRODUCT_PLAN §2, §4.6, §12 (критерий фазы 3: «ассистент объясняет ситуацию цифрами из фазы 2 и предлагает действия, которые проходят валидацию Booking Engine; ни одно действие не применяется без подтверждения»); REVIEW_2026-10-23 §3.3. До этого: `chat()` не вызывал инструменты, `drafts` всегда пуст, системный промпт содержал дамп фактов, история не отправлялась; `confirmDraft` применял только `draft_booking`, остальные молча «подтверждал»; псевдонимы на `String.hashCode` в памяти JVM.

## 1. Решения кратко
1. Порт провайдера — многоходовый `chat(LlmRequest) -> LlmResponse`: роли system/user/assistant/tool, определения инструментов, `tool_calls`, `finish_reason`, `usage`. DeepSeek — OpenAI-совместимый формат; stub — скриптуемая очередь ответов плюс «директивный» режим для E2E.
2. LLM никогда не видит UUID, имён и телефонов. Все сущности — непрозрачные ссылки `CLIENT_n`, `MASTER_n`, `VISIT_n`, `SERVICE_n`, `DRAFT_n`, стабильные внутри диалога; обратная карта — таблица `ai_pseudonym` под RLS. Тексты для провайдера строятся только из allow-list DTO инструментов; свободный текст проходит `FreeTextMasker` (regex + стоп-слова по клаузам) и подстановку ссылок по известным именам.
3. Черновик — версионированный payload; предварительная проверка при создании через публичные read-порты, полная валидация движка при подтверждении, TTL 24 ч, проверка «не устарел», подтверждение под блокировкой строки с условным переходом статуса; отказ движка → `FAILED` с кодом движка. Аудит: `ai.draft.create` (AI) → `ai.draft.confirm` (OWNER) → запись движка по `entity_id`.
4. Бюджет — `ai_usage_month (tenant_id, period 'YYYY-MM')`, период в зоне арендатора, check-then-add с атомарным upsert-инкрементом (перебор не больше одного вызова на параллельный чат); превышение → 429 `AI_BUDGET_EXCEEDED`; проверка перед каждым вызовом LLM в цикле; `confirmDraft` LLM не вызывает и работает всегда.
5. Журналы — `ai_prompt_log` (расширить) и новый hypertable `ai_tool_call`: только маскированное содержимое, ровно то, что ушло и пришло; ретенция 30 дней политикой TimescaleDB; диалоги и псевдонимы удаляет ночная задача по арендаторам.
6. История — `ai_message` (маскированные тексты user/assistant); в LLM уходят последние N финальных реплик без промежуточных обменов с инструментами; UI получает восстановленный текст по карте из БД (переживает рестарт).
7. API: типизированные `DraftAction` трёх типов, `confirm/reject` возвращают карточку, новые `GET/PUT /api/app/ai/settings`, контракт в `openapi.yaml`, карточки во фронтенде, баннер бюджета, `ApiError.code`.

## 2. Порт провайдера и цикл инструментов

### 2.1 Порт (`com.cadence.ai.internal.llm`)
```java
public interface LlmProvider {
    String provider(); String model();
    LlmResponse chat(LlmRequest request);          // заменяет complete(system, user)
    default void validate() {}
}
record LlmRequest(List<LlmMessage> messages, List<ToolSpec> tools, ToolChoice toolChoice /*AUTO|NONE*/, int maxCompletionTokens) {}
sealed interface LlmMessage permits LlmMessage.System, LlmMessage.User, LlmMessage.Assistant, LlmMessage.ToolResult {
    record System(String content) implements LlmMessage {}
    record User(String content) implements LlmMessage {}
    record Assistant(String content /*nullable*/, List<ToolCall> toolCalls /*пустой при тексте*/) implements LlmMessage {}
    record ToolResult(String toolCallId, String toolName, String contentJson) implements LlmMessage {}
}
record ToolCall(String id, String name, String argumentsJson) {}
record ToolSpec(String name, String description, JsonNode parametersSchema) {}
record LlmResponse(LlmMessage.Assistant message, FinishReason finishReason /*STOP, TOOL_CALLS, LENGTH, OTHER*/, Usage usage) {}
record Usage(int promptTokens, int completionTokens) { int total() {...} }   // нули, если провайдер не отдал
```
- `DeepSeekLlmProvider`: `messages` сериализуются по ролям (assistant с `tool_calls[]`, tool с `tool_call_id`), `tools: [{type:"function", function:{name, description, parameters}}]`, `tool_choice`, `max_tokens`; из ответа читаются `message.content`, `message.tool_calls[].{id, function.name, function.arguments}`, `finish_reason`, `usage.{prompt_tokens, completion_tokens}`. Существующий маппинг ошибок (`LLM_UNAUTHORIZED`/`LLM_UNAVAILABLE`, ключ не утекает) сохраняется. Ответ без текста и без tool_calls → `LLM_UNAVAILABLE`. `validate()`: при `thinking != disabled` отказ старта («tool loop с thinking не поддержан», TD-41).
- `StubLlmProvider`: потокобезопасная очередь `enqueue(LlmResponse...)`, `recordedRequests()`, `reset()`. Без скрипта — детерминированный текст и `Usage` ≈ `chars/4`. Директивный режим для будущего Playwright: текст пользователя `>>tool <name> <json>` → этот tool call, на следующем шаге (есть `ToolResult`) — текст с кратким JSON результата; `>>text ...` — текст.
- IT делят один Spring-контекст и идут последовательно; `app.ai.provider=stub` уже стоит в `AbstractTimescaleIT`. Тест автовайрит `StubLlmProvider`, делает `reset()` в `@BeforeEach` и ставит очередь ответов на ход.

### 2.2 Ход (`AiAssistantServiceImpl.chat`)
Нетранзакционный оркестратор: соединение не держится через HTTP-вызов LLM; каждый шаг — короткая транзакция через store/порты модулей.
1. `assertOwner()`; `message` ≤ `max-user-message-chars` (4000), иначе 400 `AI_MESSAGE_TOO_LONG`; `locale` ∈ {en,ru,ka,uk,cs}, иначе `en`.
2. `conversationId == null` → новая `ai_conversation`; иначе `requireConversation` под RLS → 404 `CONVERSATION_NOT_FOUND`.
3. `TokenBudget.assertAvailable(tenant)` — до записи чего-либо.
4. `OwnerMessageMasker` → `ai_message(role=USER, content=masked)`.
5. Контекст: `SystemPromptFactory` + последние `max-history-messages` (20) сообщений + текущее.
6. Пока `iterations < max-tool-iterations` (6) и `toolCallsThisTurn < max-tool-calls-per-turn` (10): проверка бюджета → `provider.chat` (с переключением на fallback, как раньше) → учёт `usage` → `ai_prompt_log`; при `TOOL_CALLS` — выполнить вызовы по порядку (`ToolRegistry`), результат ≤ `max-tool-result-chars` (4000, с `"truncated":true`), `ai_tool_call`, добавить `Assistant(toolCalls)` + `ToolResult` в контекст; иначе — финальный текст.
7. Лимит достигнут → ровно один дополнительный вызов с `toolChoice=NONE` («сформулируй ответ по полученным данным»); при ошибке → 502 `LLM_UNAVAILABLE`.
8. `ai_message(role=ASSISTANT, masked final, provider, model)`, `ai_conversation.last_message_at`.
9. Ответ: `ChatResponse(reply = Restorer.restore(...), drafts этого хода, conversationId, usage{monthTokensUsed, monthTokenBudget})`.

Ошибки инструментов не прерывают ход: LLM получает `{"error":{"code":"...","message":"..."}}`; неожиданное исключение → `TOOL_FAILED` и лог со стеком у нас. Коды: `UNKNOWN_REF`, `INVALID_ARGUMENTS`, `RANGE_TOO_LARGE`, `DRAFT_LIMIT`, `NOT_DRAFTABLE`, `TOOL_FAILED` и коды движка/каталога (`SLOT_OCCUPIED`, `MIN_NOTICE`, `BEYOND_HORIZON`, `SERVICE_NOT_OFFERED`, `SERVICE_INACTIVE`, `INVALID_TRANSITION`, `APPOINTMENT_NOT_FOUND`).
Актор во время инструментов — тот же OWNER (ассистент только читает и пишет черновики). `Role.AI` не используется: проверки `isOwner()` дали бы скрытые 403; происхождение AI фиксируется в своих записях аудита и `source="AI"`.

### 2.3 Инструменты (`com.cadence.ai.internal.tools`, `AiTool { name(); spec(); ToolResult execute(ToolContext, JsonNode args); }`)
Время во входах и выходах — локальное арендатора `YYYY-MM-DDTHH:MM`; все ссылки — refs; схемы аргументов с `additionalProperties:false`.
| Инструмент | Аргументы | Порт | Результат для LLM (allow-list record) |
|---|---|---|---|
| `get_schedule` | `dateFrom`, `dateTo` (≤7 дней), `masterRef?` | `BookingService.calendar` | `visits[{visitRef, masterRef, clientRef, serviceName, start, end, status}]`, ≤200, `truncated` |
| `find_free_slots` | `masterRef`, `serviceRef`, `dateFrom`, `dateTo` (≤7) | `BookingService.freeSlots` | `slots[local]`, ≤100, `truncated` |
| `get_client_profile` | `clientRef` | `CrmService.findById`, `BookingService.clientHistory`, `RetentionService.assess` | `clientRef, completedVisits, noShows, lateCancels, firstVisit, lastVisit, completedValueMinor, currency, trust{level, reasonCodes}, upcoming[≤…], recent[≤10], notes: masked \| "[withheld by tenant setting]", tags: masked` |
| `get_metrics` | `masterRef?` | `GrowthService.currentMetrics` | `[{key, value, unit, sampleSize, insufficientData, explanation, windowStart, windowEnd, deltaMoM, breakdown[{masterRef, value, insufficientData}]}]` |
| `draft_booking` | `clientRef`, `masterRef`, `serviceRef`, `start` | preflight + `AiDraftStore.save` | `{draftRef, type, status:"PENDING", summary:{refs}}` или `error` |
| `draft_reschedule` | `visitRef`, `newStart` | preflight + save | то же |
| `draft_cancel` | `visitRef`, `initiatedBy: business\|client`, `reason?` | preflight + save | то же |
Инвариант: ни один результат инструмента не сериализует `AppointmentView`/`ClientView`/`SpecialistView` напрямую — только записи `ToolResults` без телефона, имени, `enteredName`, telegram; `notes`/`tags` — только через `FreeTextMasker` и только при `notes_to_llm=true`. `ToolResultsLeakTest` проверяет все записи `ToolResults` рефлексией: запрещённые имена полей и `PhoneShape` по значениям фикстуры.

## 3. Псевдонимизация
### 3.1 Непрозрачные ссылки, карта в БД
- `RefKind {CLIENT, MASTER, VISIT, SERVICE, DRAFT}`, токен `KIND_n` (ASCII). Нумерация — на диалог и вид, при первом появлении; стабильна до конца диалога; разные диалоги — разные номера.
- `ai_pseudonym(tenant_id, conversation_id FK→ai_conversation ON DELETE CASCADE, kind, token_no, token, entity_id UUID NULL, display VARCHAR(512), created_at)`, `UNIQUE(tenant_id, conversation_id, token)`, `UNIQUE(tenant_id, conversation_id, kind, entity_id)`, RLS. `PseudonymRegistry.refFor(kind, entityId, display)` — транзакция с `SELECT ... FOR UPDATE` строки `ai_conversation` (сериализует нумерацию), `INSERT ON CONFLICT DO NOTHING` + чтение; `resolve(token) -> Optional<(kind, entityId)>`; `displayOf(token)`.
- Услуги — не ПДн; ссылка нужна, чтобы не передавать UUID; их названия LLM видит в системном промпте (`SERVICE_2: Haircut, 45 min, 80 GEL`).
- Аргументы от LLM: ссылка → `resolve`; неизвестная или другого вида → `UNKNOWN_REF`. LLM физически не может назвать UUID чужой сущности; RLS — второй барьер.

### 3.2 `FreeTextMasker` (заметки и теги при тумблере; второй проход по сообщению владельца)
1. regex: телефоны (≥7 цифр с разделителями, совместимо с `PhoneShape`) → `[phone]`; email → `[email]`; URL → `[url]`; адресные шаблоны (ул./д./кв./подъезд/домофон/этаж, `str.`, `apt`, `č.p.`, индексы 5–6 цифр) → `[address]`.
2. стоп-слова по клаузам: текст режется на клаузы (`.`, `;`, `,`, перенос строки); клауза со стоп-словом (совпадение по началу основы) целиком → `[sensitive]`. Списки — `backend/modules/ai/src/main/resources/ai-masking/stopwords_{ru,en,uk,cs,ka}.txt` (здоровье: аллерг-, беремен-, диабет-, эпилеп-, ВИЧ, онко-, препарат-, диагноз-, allerg-, pregnan-, diabet-, medic-…; долги: долг, задолж-, debt).
3. известные имена клиентов и мастеров из реестра диалога → ссылки.
Остаток (имя незнакомого третьего лица без стоп-слова) — принятый риск (TD-37).

### 3.3 `OwnerMessageMasker`
Кандидаты — последовательности букв Unicode длиной ≥3; совпадения с именами активных мастеров (`CatalogService.listSpecialists(false)`, без учёта регистра) и клиентами через `CrmService.search(word)` (≤5). Соседние слова, совпавшие с одним клиентом, — один фрагмент. Одно совпадение → `CLIENT_7`; 2–5 → `(CLIENT_3|CLIENT_4)`; >5 → `[name:many]`; мастер и клиент одновременно → `(MASTER_1|CLIENT_9)`. Затем regex §3.2. Ненайденное имя уходит как есть (TD-36). Следствие: `draft_booking` только для существующих клиентов.

### 3.4 `Restorer`
`\b(CLIENT|MASTER|VISIT|SERVICE|DRAFT)_(\d+)\b` → `display` из `ai_pseudonym`. Неизвестный токен остаётся как есть + WARN. Восстановление — при каждом чтении; хранится всегда маскированный текст.

## 4. Черновики
### 4.1 Payload (`DraftPayloads`, sealed + Jackson, `"v":1`, в `ai_draft.payload`)
- `draft_booking`: `{v, type, clientId, specialistId, serviceId, serviceStart (ISO UTC), summary:{clientName, specialistName, serviceName, durationMinutes, priceMinor, currencyCode, localStart}}`
- `draft_reschedule`: `{v, type, appointmentId, newStart, expected:{serviceStart, status}, summary:{clientName, specialistName, serviceName, localFrom, localTo}}`
- `draft_cancel`: `{v, type, appointmentId, target: CANCELLED_BY_BUSINESS|CANCELLED_BY_CLIENT, reason?, expected:{serviceStart, status}, summary:{clientName, specialistName, serviceName, localStart}}`
`summary` — для карточки владельца (реальные имена допустимы). `reason` от LLM проходит `Restorer` и идёт в `note` перехода.

### 4.2 Создание (внутри инструмента, своя транзакция)
Предварительная проверка через публичные read-порты: booking — `CatalogService.offering` (`offered && serviceActive`), `rules()` (минимальный срок и горизонт, как в `BookingServiceImpl.persist`), `freeSlots(day)` содержит `start`; reschedule — статус ∈ {PENDING, CONFIRMED}, `freeSlots` содержит `newStart` (собственный старый слот не исключается — TD-38); cancel — статус ∈ {PENDING, CONFIRMED}. Не прошла → черновик не создаётся, LLM получает код. `max-drafts-per-turn` 3 → `DRAFT_LIMIT`. Создан → `ai_draft(status=PENDING, expires_at = now + draft-ttl, message_id)` + аудит `ai.draft.create` (actor_type `AI`, actor_id `ai:<provider>/<model>`, entity ai_draft, after=payload, source `ai-chat`, request_id=conversationId).
Порт `BookingService.validate*` не вводится: это рефакторинг движка ради советующей проверки; движок — единственная истина при подтверждении.

### 4.3 Подтверждение (`DraftApplier`)
```
confirmDraft(id):                                  // оркестратор без @Transactional, assertOwner()
  try { applier.claimAndApply(id) }                // @Transactional
  catch (DomainException e) { store.markFailed(id, e.code(), msg) /* новая tx, WHERE status='PENDING' */; throw e; }

claimAndApply (одна транзакция):
  draft = store.requireForUpdate(id)               // SELECT ... FOR UPDATE
  status != PENDING -> 409 DRAFT_NOT_PENDING
  expires_at < clock.now -> UPDATE status=EXPIRED; 409 DRAFT_EXPIRED
  reschedule/cancel: visit = bookingService.find(appointmentId); serviceStart/status != expected -> 409 DRAFT_STALE (снаружи → FAILED/DRAFT_STALE)
  apply: create(CreateBookingCommand(..., source="AI", statusOverride=null, skipNoticeAndHorizon=false)) | reschedule(id, newStart) | transition(id, target, reason)
  UPDATE ai_draft SET status=CONFIRMED, confirmed_at, resolved_at, applied_entity_id WHERE id AND status='PENDING' (ровно 1 строка, иначе IllegalState)
  аудит ai.draft.confirm (OWNER, before=payload, after={status, appliedEntityId}, source ai-draft, request_id=draftId)
```
Отказ движка откатывает его изменения (одна транзакция); `FAILED` + `failure_code` пишется отдельной транзакцией после отката; HTTP-ответ — ошибка движка. Повторный confirm после успеха → 409 `DRAFT_NOT_PENDING`. `rejectDraft` — условный `UPDATE ... WHERE status='PENDING'`, `assertOwner()`, аудит `ai.draft.reject`. `listDrafts` перед чтением переводит просроченные `PENDING` в `EXPIRED`.
Статусы: `PENDING | CONFIRMED | REJECTED | FAILED | EXPIRED` (устаревший = `FAILED`/`DRAFT_STALE`).
Цепочка в аудите: `ai.draft.create` → `ai.draft.confirm{appliedEntityId}` → запись движка `appointment.create` (source «AI») / `appointment.transition` (source движка `console`, связь по `entity_id`, TD-40).

## 5. Бюджет
- `ai_usage_month(tenant_id, period CHAR(7), prompt_tokens BIGINT, completion_tokens BIGINT, llm_calls INT, tool_calls INT, exceeded_at TIMESTAMP NULL, updated_at; PK(tenant_id, period))`, RLS. Период — `YearMonth` в зоне арендатора; `Clock` внедряется.
- Лимит: `ai_tenant_settings.monthly_token_budget` (NULL → `app.ai.default-monthly-token-budget` = 1 000 000). Настраивает оператор; владелец видит только для чтения (TD-42).
- Перед каждым вызовом LLM — чтение суммы и сравнение; после — `INSERT ... ON CONFLICT (tenant_id, period) DO UPDATE SET prompt_tokens = ai_usage_month.prompt_tokens + EXCLUDED.prompt_tokens ...`. Reserve-then-settle отвергнут: резерв протекает при падении между резервом и расчётом, а держать соединение через HTTP-вызов нельзя.
- Превышение → `DomainException("AI_BUDGET_EXCEEDED", 429, "...used/limit, resets YYYY-MM-01 <zone>")`; первый раз в периоде — `exceeded_at` + аудит `ai.budget.exceeded` (SYSTEM). Проверка только в `chat`; `confirm/reject/conversation/drafts/settings` и весь кабинет от бюджета не зависят.
- Превышение посреди цикла: сообщение пользователя уже сохранено, созданные черновики остаются `PENDING`, финальный текст не запрашивается, ответ 429.

## 6. Журналы и аудит
- `ai_prompt_log` (hypertable): добавить `message_id UUID`, `prompt_tokens INT`, `completion_tokens INT`, `finish_reason VARCHAR(32)`, `latency_ms INT`, `tool_calls_json VARCHAR(8192)`; `prompt`/`response` → `VARCHAR(65536)`: пишется весь маскированный массив messages, как он ушёл, и ответ, как он пришёл; при переполнении — усечение с маркером (TD-43).
- Новый hypertable `ai_tool_call(id, tenant_id, conversation_id, llm_call_id, tool_name, arguments VARCHAR(4096), result VARCHAR(16384), status OK|ERROR, error_code, duration_ms, created_at; PK(id, created_at))`, `create_hypertable(..., associated_schema_name => 'cadence_chunks')`, RLS как в V13.
- Ретенция 30 дней: `add_retention_policy` на `ai_prompt_log` и `ai_tool_call`. Диалоги (`ai_conversation` → каскад `ai_message`, `ai_pseudonym`, и черновики диалога; цепочка остаётся в `audit_log`, TD-44) — `AiRetentionJob` в `app`, ночью, по арендаторам как `MetricsBatchConfig` (`TenantContext.open` + `ActorContext.system()`), `last_message_at < now − P30D`.
- Журналы содержат только маскированный текст по построению (логируется тот же `LlmRequest`, что ушёл). Строки лога приложения — tenant, conversation, токены, latency, finish_reason; никогда содержимое.
- Аудит: `ai.draft.create/confirm/reject/fail`, `ai.settings.update` (before/after), `ai.budget.exceeded`. Ходы чата в аудит не пишутся.

## 7. История диалога
- `ai_conversation(id PK, tenant_id, locale, created_at, last_message_at)`; `ai_message(id, tenant_id, conversation_id FK CASCADE, role USER|ASSISTANT, content VARCHAR(16384) маскированный, provider, model, created_at)`, порядок `(created_at, id)`, индекс `(tenant_id, conversation_id, created_at)`.
- В LLM: системный промпт + последние 20 финальных реплик + текущее сообщение. Обмены с инструментами прошлых ходов не пересылаются.
- Системный промпт (английский, без дампа фактов): роль; «answer in locale X»; текущее время в зоне арендатора, зона, валюта; услуги `SERVICE_n: name, min, price`; активные мастера `MASTER_n`; правила записи (шаг, минимальный срок, горизонт); соглашение о ссылках («никогда не выдумывай refs»); «цифры только из get_metrics, называй окно и размер выборки, при insufficientData говори об этом»; «изменения только черновиками, ≤3 за ход, применяет владелец»; формат времени.
- UI (`conversation()`): `MessageView{id, at, role, content (restored), provider, model, drafts[DraftAction]}` — черновики привязаны к ходу через `ai_draft.message_id`; плюс `drafts` диалога целиком.

## 8. API, контракт, фронтенд
- `POST /ai/chat` → `ChatResponse{reply, drafts: DraftAction[], conversationId, usage{monthTokensUsed, monthTokenBudget}}`.
- `DraftAction{id, type, status, createdAt, expiresAt, resolvedAt, appliedEntityId, failureCode, failureMessage, summary:{типизированные поля §4.1}}` вместо строки `payloadJson`.
- `POST /ai/drafts/{id}/confirm|reject` → `DraftAction`; 409 `DRAFT_NOT_PENDING|DRAFT_EXPIRED|DRAFT_STALE`, коды движка; 404 `DRAFT_NOT_FOUND`.
- `GET /ai/conversations/{id}` → 404 `CONVERSATION_NOT_FOUND` для невидимого под RLS.
- `GET /ai/settings` → `AiSettingsView{notesToLlm, monthlyTokenBudget, monthTokensUsed, period, periodResetsAt}`; `PUT /ai/settings` `{notesToLlm}` (только владелец, аудит). В `openapi.yaml` с 200/401/403.
- `MoneyFieldClassifierTest`: новые нечисловые-денежные компоненты (`durationMinutes`, `monthTokensUsed`, `monthTokenBudget`, `monthlyTokenBudget`) — в `NOT_MONEY` с причинами; `priceMinor` классифицируется сам.
- 429 `AI_BUDGET_EXCEEDED` проходит через `ApiExceptionHandler.onDomain` без изменений.
- Фронтенд (`frontend/app/(console)/ai.tsx`, `frontend/src/api/client.ts`): разбор problem+json в `ApiError{status, code, detail}`; для AI-эндпоинтов убрать `optionalRequest` и ключи `ai.historyUnavailable`/`ai.rejectUnavailable`; карточки по `type` (booking: клиент, мастер, услуга, локальное время, длительность, цена через `formatMoney`; reschedule: «с → на»; cancel: время, инициатор, причина); бейджи статусов, кнопки только у `PENDING`; баннер `AI_BUDGET_EXCEEDED` с использованием, лимитом и датой сброса; при 404 `CONVERSATION_NOT_FOUND` — сбросить сохранённый id диалога; кнопка «Новый диалог»; панель настроек AI на том же экране: тумблер «передавать заметки клиентов в AI (маскированно)» и индикатор бюджета. Все подписи — ключи i18n en/ru.

## 9. Схема: V15 + `db/jooq/schema.sql`
1. `ai_tenant_settings(tenant_id PK FK, notes_to_llm BOOLEAN NOT NULL DEFAULT false, monthly_token_budget BIGINT NULL, updated_at)`.
2. `ai_conversation`, `ai_message`, `ai_pseudonym`.
3. `ai_usage_month`.
4. `ai_draft`: `ADD expires_at, resolved_at, applied_entity_id UUID, failure_code VARCHAR(64), failure_message VARCHAR(1024), message_id UUID`; `UPDATE SET expires_at = created_at + 24h WHERE status='PENDING'`; индекс `(tenant_id, status, created_at)`.
5. `ai_prompt_log`: колонки §6; `prompt/response TYPE VARCHAR(65536)`.
6. `ai_tool_call` hypertable + RLS; `add_retention_policy` на оба hypertable.
7. RLS + политика `tenant_isolation` на все новые таблицы (образец V13); гранты — default privileges V7; чанки — задача V11.
8. Платформа: `TenantInfo(id, slug, displayName, timezone, currencyCode, countryCode)` + `TenantDirectory.infoById(UUID)` — `ai` не может использовать jOOQ-тип `TenantRecord`.
9. ArchUnit: таблицы `Ai*` доступны только `com.cadence.platform.ai..`.

## 10. Конфигурация `app.ai.*`
`default-monthly-token-budget: 1000000`, `max-tool-iterations: 6`, `max-tool-calls-per-turn: 10`, `max-drafts-per-turn: 3`, `max-tool-result-chars: 4000`, `max-history-messages: 20`, `max-user-message-chars: 4000`, `max-completion-tokens: 1024`, `draft-ttl: PT24H`, `retention: P30D`, `retention-cron: "0 40 3 * * *"`.

## 11. Риски
1. Утечка ПДн через неучтённое поле или неопознанное имя — `ToolResultsLeakTest` (структурно), IT-сценарий по данным сида, ручная проверка `ai_prompt_log` при живой проверке; остаток — TD-36.
2. Расхождение формата tool calling DeepSeek с реализацией (допущение) — живая проверка по README; меняется только `DeepSeekLlmProvider`. План Б: structured output тем же портом.
3. Двойное применение или применение к изменившемуся визиту — `FOR UPDATE` + условный апдейт + `expected` в payload; IT-сценарии.
4. Перебор бюджета параллельными вызовами — не больше одного вызова; `exceeded_at` и аудит показывают частоту.
5. Рост `ai_prompt_log` — ограничен контекстом и ретенцией.

## 12. Отложено
Порт `BookingService.validate*`; запись новых клиентов через AI; `draft_client_message`/`draft_broadcast` (фаза 4), `get_growth_signals` (фаза 5); thinking-режим с инструментами; стриминг; список диалогов в UI; fuzzy-поиск имён и NER для заметок; настройка бюджета владельцем и стоимость в валюте; предпросмотр «что увидит AI»; `source` для reschedule/transition в движке; Playwright E2E AI (финальное тестирование; директивный режим stub подготовлен); DPA и EU-хостинг провайдера (продуктовое и юридическое решение).

## 13. Примечания реализации
Отклонения от текста выше и решения, принятые при реализации и ревью этапа 05.

**Маскирование и сопоставление имён**
- `OwnerMessageMasker` — один пакетный вызов `CrmService.findByNameWords`, а не поиск по слову.
- Имена сопоставляются через `crm.api.NameWords` (решение пользователя). Точное совпадение ключей подходит всегда. Иначе слова должны иметь общую основу и различаться только окончаниями из одного списка `ENDINGS` (падежные и фамильные окончания ru/uk/cs/ka); основа не короче 3 букв, оба слова не короче 4. Поэтому `верно`/`Вера`, `лень`/`Лена`, `market`/`Mark`, `завтрак`/`Завтра`, `дано`/`Дана`, а также `Петр`/`Петрова` (суффиксы `ов`, `ова` в список не входят) не совпадают; `верю`/`Вера` совпадает (принято). Свёртка: диакритика, `ё`/`е`, `й`/`и`, апострофы (`Мар'яна` — одно слово). Мастера и `NameIndex` используют то же правило.
- Точки разреза: основой считается любой префикс общего префикса, от самого длинного до минимальной длины основы (`Шевченком`/`Шевченко`: основа `шевченк`, окончания `ом` и `о`; `Толстого`/`Толстой`).
- Короткие имена: сохранённое имя ровно из 3 букв совпадает и через основу из 2 букв, если набранное слово не короче 3 букв и начинается с заглавной (`Олю`/`Оля`, `Евой`/`Ева`); со строчной — только точно, чтобы ограничить ложные срабатывания. В грузинском нет заглавных в обычном письме, его слова считаются заглавными (`ანას`/`ანა`).
- Окончания `о` и `ь` допустимы только у сохранённого имени (`Шевченко`/`Шевченка`, `Игорь`/`Игоря`), иначе они дали бы ложные основы. Следствие: звательный падеж (`Яно`, `Анно`) не распознаётся.
- `CrmService.findByNameWords` — предфильтр одним запросом с bind-параметрами: шире правила или равен ему, никогда не уже; решает `NameWords.matches` на стороне Java, поскольку SQL не знает регистр первой буквы. Ровно 2000 строк по-прежнему означают закрытый отказ (`[name:many]`).
- В сообщении владельца регулярные выражения (телефоны, ссылки) выполняются до поиска имён; прохода по стоп-словам там нет. В свободном тексте стоп-слово маскирует всё предложение.
- Почтовые индексы маскируются только в контексте адреса; хэндлы (`@name`) и хосты маскируются. Запасной вариант для телефонов — широкая серия: ≥9 цифр, между ними ≤3 не-буквы.
- Текст заметок, похожий на токен, превращается в `[ref]`.
- `OwnerMessageMasker` и поиск не нормализуют опечатки и чередования (`Павла`/`Павел`, `Льва`/`Лев`; чередования приняты пользователем): остаток — TD-36.

**Схема (V15)**
- `entity_id NOT NULL` в `ai_pseudonym`; составной FK `(tenant_id, conversation_id)` на `ai_conversation`; `ai_draft` каскадно удаляется вместе с диалогом; `CHECK`-ограничения на статусы, роли, длины и бюджет.
- Строка диалога блокируется `FOR NO KEY UPDATE`, а не `FOR UPDATE`: `FOR UPDATE` держит несовместимый с `FOR KEY SHARE` замок, который FK-проверки дочерних вставок берут сами, и две транзакции взаимно блокировались (дедлок).
- Порядок проверок: бюджет — до создания диалога (отказ не оставляет пустого диалога).

**Ход чата и черновики**
- Профиль клиента берёт `RetentionService.compute`, а не `assess`.
- Статус `EXPIRED` записывается после отката транзакции подтверждения.
- `DraftApplier` работает на `TransactionTemplate`, а не на `@Transactional`.
- Текст отказа порта в LLM не уходит — только код. Пояснение метрики (`explanation`) не маскируется: в нём только числа (проверено).
- `clientId` есть во всех payload черновиков. В preflight и при подтверждении клиент читается `findActiveById`.
- Аудит черновиков — через `forAudit` без `summary` и `reason`; `reason` не пишется в заметку визита.
- `reason` хранится в payload со ссылками, как его написала модель (длиннее 500 символов — `INVALID_ARGUMENTS`, без обрезки: `CLIENT_12` не должен стать `CLIENT_1`), и восстанавливается `Restorer` при чтении карточки. Поэтому замены `display` в `ai_pseudonym` достаточно, чтобы имя выбывшего клиента исчезло и из причин чужих черновиков.
- `ClientRetirementListener` / `ClientDataScrubber` вычищают данные клиента при анонимизации и слиянии; порядок замков: черновики, затем клиент. `expireOverdue` блокирует строки в порядке id, как `lockByClient`.
- Слияние обрабатывается как анонимизация: CRM стирает имя поглощённого профиля, поэтому в AI-данных копии нет, история показывает `anonymized`, а не имя выжившего; черновики поглощённого становятся `FAILED`/`DRAFT_STALE`. Визиты переходят к выжившему, поэтому ссылка `VISIT_n` остаётся рабочей и новый черновик по ней относится уже к выжившему.
- Отказ подтверждения из-за выбывшего клиента (`DRAFT_STALE`) вычищает имя и `reason` и из самого черновика, каким бы путём клиент ни выбыл.
- Для непарсимых аргументов tool call в историю пишется `{}`. Исчерпание лимитов — `TOOL_LIMIT`; финальный вызов принудительного ответа содержит системное сообщение. При `LENGTH` к ответу добавляется ` […]`. При нулевом `usage` токены считаются как `chars/4`.
- Неудачные попытки провайдера пишутся в `ai_prompt_log` с `finish_reason = ERROR`.
- Мастера регистрируются раньше услуг (маскирование подписей). Лимит строк расписания применяется до выдачи ссылок.

**Запуск и контракт**
- Самопроверка локали БД при старте (`NameMatchingSelfCheck`); на реальной БД с локалью `C` она не прогонялась (см. tech-debt).
- Новые коды ошибок: `AI_MESSAGE_EMPTY`, `INVALID_DRAFT_STATUS`, `DRAFT_INVALID`, `INVALID_AI_SETTINGS`.
- `AiRetentionJob` (`app.batch`, по образцу `MetricsBatchConfig`) читает `app.ai.retention` и `app.ai.retention-cron` через `@Value`, потому что `AiProperties` принадлежит модулю `ai`, а удаление идёт через порт `AiConversationStore.deleteOlderThan` платформы; сбой одного арендатора логируется и не останавливает остальных. Тест — `AiRetentionJobIT`.
