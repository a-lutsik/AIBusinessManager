# ADR 0001 — Метрики роста считаются по журналу событий бронирования

Статус: принято 2026-10-25 (архитектор; определения REPEAT_RATE и NO_SHOW_RATE подтверждены пользователем).
Контекст: PRODUCT_PLAN §4.5, §12 (критерий фазы 2: «каждая метрика воспроизводима вручную из журнала событий, расхождений с дашбордом нет»). До этого метрики считались из текущего состояния `appointment`, журнал `appointment_event` нёс только статусы, слияние клиентов переписывало `client_id` без события.

## Решение
Журнал становится самодостаточным. booking-engine владеет чистой свёрткой собственного журнала и отдаёт её через api-порт `BookingJournal`; growth считает шесть метрик чистыми функциями от `VisitFact`. Строки `appointment` остаются операционными и для метрик служат проверяемой производной: каждая пересборка метрик сверяет свёртку со строками и пишет расхождения в аудит.

Отвергнуто: (c) считать из `appointment` и доказывать эквивалентность повтором — журнал не содержит мастера, клиента, времени и цены, а merge не пишет событий, поэтому «доказательство» было бы циклическим; (b) материализованная таблица фактов — второй путь записи без выигрыша при объёмах сотни событий в месяц (порт позволяет добавить её позже без изменения growth).

## Спецификация

### 1. Журнал (booking-engine)
- `enum AppointmentEventType { CREATED, CONFIRMED, RESCHEDULED, CLIENT_CHANGED, COMPLETED, NO_SHOW, CANCELLED, MASTER_CHANGED, SERVICE_CHANGED, PRICE_CHANGED, REMINDER_SENT }` в `com.cadence.booking.api`, хранится как `.name()`. Свёртка перебирает типы исчерпывающе, без `default`; неизвестная строка в БД — исключение и громкий провал пересчёта арендатора.
- Payload — JSON через один класс `booking.internal.JournalPayloads` (Jackson, без конкатенации), в каждом `"v":2`:
  - `CREATED`: `specialistId, serviceId, clientId, status, serviceStart, serviceEnd, priceMinor, discountMinor, durationMinutes, currency, serviceName, source`;
  - `CONFIRMED | COMPLETED | NO_SHOW | CANCELLED`: `from, to` (статусы; `to` хранит `CANCELLED_BY_CLIENT`/`CANCELLED_BY_BUSINESS`), плюс `"correction":true` для исправления владельцем NO_SHOW↔COMPLETED;
  - `RESCHEDULED`: `from, to` — начала услуги (ISO-8601 UTC); конец = `to + durationMinutes` из CREATED;
  - `CLIENT_CHANGED` (новое): `from, to, reason:"MERGE"`, по одному событию на каждый перепривязанный визит, в транзакции merge;
  - `MASTER_CHANGED | SERVICE_CHANGED | PRICE_CHANGED` — зарезервированы: ветка свёртки бросает «not emitted yet», пока нет пути записи; `REMINDER_SENT` — на факты не влияет.
- Время события — из внедрённого `Clock`, не `Instant.now()`.
- Порядок внутри визита — `(created_at, id)`; записи визита уже сериализованы `findForUpdate`; поля `from` делают цепочку самопроверяемой.
- Единая точка мутации: package-private `AppointmentRepository.record(AppointmentView after, AppointmentEventType type, Object payload)` пишет изменение строки и событие вместе; `insert`, `updateStatusAndTimes`, `reassignClient` сводятся к нему. `revealToMaster` и `note` — не факты, перечислены в Javadoc.

### 2. Миграция V13 и `db/jooq/schema.sql`
- Бэкфилл каждого `CREATED` без `"v":2` из `appointment` через `json_build_object`: текущие мастер/услуга/клиент/цена/скидка/длительность/валюта/название/источник, `status` из старого payload, `serviceStart` = самое раннее `RESCHEDULED.from` визита либо `service_start`, `"backfilled":true`. Для визита без событий вставить `CREATED` с `created_at = appointment.created_at`. Слияния до V13 не восстановимы по визитам — бэкфилл несёт текущего (выжившего) клиента; указать в шапке миграции.
- `growth_signal` — из hypertable в обычную таблицу: `PRIMARY KEY (id)`, `dedupe_key VARCHAR(256) NOT NULL`, `specialist_id UUID NULL`, `period VARCHAR(7) NOT NULL`, `updated_at TIMESTAMP NOT NULL`, `UNIQUE (tenant_id, dedupe_key)`; строки не переносить; RLS и политика `tenant_isolation` по образцу V4; гранты — из default privileges V7. `dismissSignal` теряет предикат по `created_at`.
- Индекс `appointment_event (tenant_id, appointment_id, created_at)`.
- Отразить `growth_signal` и индекс в `db/jooq/schema.sql`. `metric_snapshot` без изменений: его `created_at` — момент as-of свёртки.

### 3. Порты и границы модулей
```java
// com.cadence.booking.api
public record VisitFact(UUID appointmentId, UUID specialistId, UUID serviceId, UUID clientId,
        AppointmentStatus status, Instant serviceStart, Instant serviceEnd, int priceMinor, int discountMinor,
        int durationMinutes, String currencyCode, String serviceName, String source, Instant createdAt) {}
public record JournalDefect(UUID appointmentId, String kind, String detail) {} // NO_CREATED, CHAIN_BREAK, ROW_MISMATCH, ORPHAN_EVENT
public record JournalFold(Instant asOf, List<VisitFact> facts, List<JournalDefect> defects) {}
public interface BookingJournal {
    /** Все визиты текущего арендатора после свёртки событий с created_at <= asOf. Только OWNER/SYSTEM (403 для MASTER). */
    JournalFold fold(Instant asOf);
    /** fold(asOf) против строк appointment: расхождения полей, визиты без событий, события без визита. */
    List<JournalDefect> reconcile(Instant asOf);
}
```
- Свёртка — чистая статическая `booking.internal.VisitJournalFold.fold(List<EventRow>, Instant asOf)`; `BookingJournalImpl` читает jOOQ. Первое событие обязано быть CREATED; статусные события проверяют `from == текущий` и ставят `to`; RESCHEDULED проверяет `from == текущее начало`; CLIENT_CHANGED проверяет `from`. Несовпадение — дефект `CHAIN_BREAK` (событие всё равно применяется), никогда не молча.
- Growth берёт `BookingJournal` вместо `BookingService.calendar`; `MetricsCalculator.compute(List<VisitFact>, расписание, ZoneId, ws, we, asOf, specialistId)` — чистая функция. Выделить `MetricSnapshotRepository` и `GrowthSignalRepository` в `growth.internal`.
- Доступные минуты: правило дня переезжает из `SlotCalculator` в чистый `com.cadence.catalog.api.WorkingHours.windowsFor(LocalDate, weekly, exceptions)` + `availableMinutes(ZoneId, from, to, weekly, exceptions)`; `SlotCalculator` делегирует. `DAY_OFF` → 0, `SHIFT` → окно смены заменяет недельные, прочее → недельные.
- ArchUnit: от таблиц `Appointment`/`AppointmentEvent` зависит только `com.cadence.booking.internal..`, от `MetricSnapshot`/`GrowthSignal` — только `com.cadence.growth.internal..`.

### 4. Окна и формулы
Z — зона арендатора; W — даты [ws, we] в Z, как интервал Wi = [начало ws, начало we+1); T — asOf = `metric_snapshot.created_at`; F — факты `fold(T)`; «в W» — `serviceStart ∈ Wi`; day(x) — дата x в Z. Срез по мастеру — те же определения на F, отфильтрованных по мастеру. Минимальные выборки берутся из `MetricDefinitions` (тест сверяет с калькулятором).
1. **SLOT_UTILIZATION**: S — мастера, активные на T. Числитель — сумма минут пересечения интервала услуги с Wi для фактов в занимающих статусах и мастеров из S. Знаменатель — сумма по s∈S, d∈W окон `WorkingHours.windowsFor(d)` в абсолютных минутах. Недостаточно данных при знаменателе 0. Пояснение: числитель, знаменатель, число применённых исключений.
2. **REPEAT_RATE** (подтверждено пользователем): P — клиенты с ≥1 COMPLETED в W; R — из P те, у кого ≥2 COMPLETED со `serviceStart < конец Wi` за всю историю. value = |R|/|P|; недостаточно при |P| < 10.
3. **RETURN_INTERVAL**: для c∈P с ≥2 COMPLETED до конца Wi — последовательные COMPLETED по `serviceStart` (ничья — по appointmentId); gap = DAYS между day(start_i) и day(start_i+1); m_c — медиана (чётное — среднее двух средних, дробное). value — медиана {m_c}; недостаточно при < 3 клиентах.
4. **NO_SHOW_RATE** (подтверждено пользователем): D — факты в W с `serviceStart < T` в занимающих статусах (PENDING, CONFIRMED, COMPLETED, NO_SHOW); N — из D с NO_SHOW. value = |N|/|D|; недостаточно при |D| < 5. Пояснение сообщает, сколько из D ещё не отмечено.
5. **REVENUE_PER_HOUR**: C — COMPLETED в W; value = Σ(price − discount) / (Σduration/60) в валюте арендатора; недостаточно при Σduration = 0.
6. **CHURN_RISK** (метрика на момент T, хранится с тем же окном): K — клиенты с ≥1 COMPLETED в [T−365д, T]. last_c — последний COMPLETED ≤ T; days_c = DAYS(day(last_c), day(T)); typical_c — медиана интервалов за всю историю при ≥2 COMPLETED, иначе медиана арендатора (по клиентам с ≥2 COMPLETED); клиенты без typical исключаются и считаются в пояснении. HIGH при days_c ≥ 2·typical_c, WARNING при ≥ 1.5·typical_c. value = |HIGH|/|K|; недостаточно при |K| < 5. Список оценок по клиентам сохраняется внутри для сигналов и реактивации.

Воспроизведение старого снимка: свёртка с asOf = `created_at` снимка. Поздняя корректировка меняет следующий снимок, но не воспроизведение старого. Расписание — единственный вход вне журнала.

### 5. Окна пересчёта и сигналы
- Ночной job и `POST /metrics/recalculate` считают по арендатору в его зоне (`LocalDate.now(clock.withZone(zone))`) текущий месяц до даты и предыдущий полный месяц. `deltaMoM` — только к предыдущему календарному месяцу.
- Сигналы (арендатор, текущий месяц): `dedupe_key = signalKey + ":" + ("tenant" | specialistId) + ":" + YYYY-MM(ws)`; `INSERT … ON CONFLICT (tenant_id, dedupe_key) DO UPDATE SET evidence, severity, action_payload, updated_at`, не трогая `dismissed_at`. Правила без валюты, только по метрикам с достаточными данными:
  - `NO_SHOW_SPIKE`: NO_SHOW_RATE ≥ 0.15 → WARNING, evidence «N из D визитов»;
  - `UTILIZATION_GAP`: SLOT_UTILIZATION < 0.40 → WARNING, evidence «num / den минут»;
  - `LOW_VALUE_SERVICES`: по услуге с ≥3 COMPLETED в W, если её стоимость в час < 0.5 × REVENUE_PER_HOUR арендатора; evidence — доля («услуга приносит 38% вашего среднего в час за 5 визитов»), суммы — только в `action_payload`.

### 6. Тесты
- Золотой набор (~30 фактов, 2 мастера, ~14 клиентов, целевой месяц и история) с ожидаемыми значениями, посчитанными вручную, и арифметикой в тексте теста; по одному случаю «недостаточно данных» на метрику; срез по мастеру.
- Чистые юнит-тесты: `VisitJournalFoldTest`, `WorkingHoursTest` (включая DST), `MetricsCalculatorTest`, `MetricDefinitionsTest`.
- `MetricsJournalReplayIT`: все пути записи через реальные сервисы, пересчёт, затем независимое SQL-воспроизведение (`src/test/resources/metrics-replay.sql`) как владелец — равенство с последними `metric_snapshot` (1e-9); `reconcile` без дефектов; двойной пересчёт не дублирует сигналы; скрытый сигнал не воскресает. Этот SQL — письменная процедура ручной проверки.
- Полнота журнала: каждый мутирующий метод `BookingService` пишет ровно одно событие на изменённый визит; правило ArchUnit.
- `JournalBackfillMigrationIT`: Flyway до 12, «старый» визит, миграция до последней, проверка v2-payload с исходным началом и `backfilled:true`, `growth_signal` — обычная таблица с уникальным индексом.

## Главный риск
Неполнота журнала: путь записи, меняющий поле факта без события. Обнаружение: `reconcile` в каждом пересчёте пишет аудит `growth.journal_mismatch`; replay-IT покрывает все пути; единая точка `record(...)` и правило ArchUnit. Ремонт — дописать недостающее событие, историю не переписывать.

Второстепенные: расписание не версионируется (изменение шаблона переписывает знаменатели прошлых окон); сдвиг минут дня на DST — общий для слотов и метрик, чинится в `WorkingHours`; `schedule_exception.kind` — свободный текст.

## Уточнения после ревью (2026-10-26, решения пользователя)
- **Визиты одного дня — один приход.** Для RETURN_INTERVAL, CHURN_RISK и REPEAT_RATE завершённые визиты клиента группируются по дню (day(serviceStart) в зоне арендатора): интервалы считаются между разными днями визитов, «≥2 завершённых» означает ≥2 разных дня. Иначе два визита в один день давали интервал 0 и мгновенный высокий риск оттока.
- **Сигналы снимаются сами.** Если правило в текущем периоде больше не срабатывает, строка сигнала этого периода помечается решённой и исчезает из открытых; на дашборде — только сигналы текущего периода. История остаётся в базе.
- **Детерминизм входов:** исключения графика на одну дату выбираются в одном порядке в Java и в SQL-воспроизведении; asOf пересчёта отстаёт от текущего времени на запас, больший самой длинной транзакции записи; время события внутри визита монотонно.

## Примечания реализации (2026-10-26, приёмка этапа 04)
Отклонения от текста выше, принятые при приёмке:
- `AppointmentRepository.record(...)` принимает запечатанный `JournalPayloads.Payload`, а не `Object`; `requireFits` отклоняет несоответствие типа события и payload до записи.
- Порядок ничьих `(created_at, id)` — беззнаковое сравнение uuid, как в PostgreSQL, чтобы свёртка в Java и SQL-воспроизведение упорядочивали одинаково.
- `sampleSize` — величина, сравниваемая с минимумом: доступные минуты для SLOT_UTILIZATION, завершённые минуты для REVENUE_PER_HOUR, счётчики для остальных метрик.
- Даты окон и период сигналов выводятся из asOf (T = now − `app.metrics.as-of-lag`), а не из `now`; `reconcile` сверяет текущие строки с журналом на `now`. Демо-сид пересчитывает без лага через отдельный внутренний путь (единственный писатель при старте, не публикуется по HTTP).
