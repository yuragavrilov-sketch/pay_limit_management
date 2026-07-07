# Этапы 5–7 (аудит, effective-limits+метрики→M2, сид) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Укрупнённые задачи (по запросу пользователя): каждая задача — законченный слой; TDD выполняется ВНУТРИ задачи; шаги описывают deliverable, а не по-строчный цикл.

**Goal:** Довести `pay_limit_management` до конца: аудит операций (этап 5), предпросмотр действующих лимитов + метрики → веха **M2** (этап 6), сид первой группы (этап 7).

**Architecture:** Гексагональная. Аудит и operator-контекст — новые порты + Spring-адаптеры; запись аудита атомарна с мутацией через уже существующий `TransactionRunner` (введён на этапе 3, `common/invariant/port/TransactionRunner` + `SpringTransactionRunner`). effective-limits — чистая доменная резолюция по приоритету уровней. Метрики — Micrometer. Сид — Flyway data-миграция в DRAFT.

**Tech Stack:** Java 21 records, Spring Boot, PostgreSQL, JdbcTemplate + Flyway, Micrometer, JUnit 5 + Mockito, Testcontainers, Maven.

## Global Constraints
- Русский пользователю; код/имена/комментарии английские. Без Lombok; constructor injection; JdbcTemplate; forward-only Flyway; Maven.
- Деньги строками/BigDecimal; время через `Clock`; календарные окна в `businessTimezone` (Europe/Moscow); синтетические идентификаторы в тестах; **никаких PAN/телефонов/счетов** (только HMAC + маска — но в management их нет вовсе).
- Иммутабельность: `audit_event` append-only; манифесты не трогаем. Fail-closed не ослаблять.
- Аудит пишется **в той же транзакции**, что и изменение (`TransactionRunner`). Мутация без `X-Operator-Id` → **400**, изменение не применяется.
- Не ломать существующее (модель правила, инвариант, GLOBAL, манифест v2/checksum-контракт M1). effective-limits считается по текущей конфигурации management, **без обращения к счётчикам engine**.
- Коммиты `feat:`/`fix:` после каждой задачи; **без push** без просьбы. Не переходить к следующей задаче с красными тестами.
- Сборка (нет mvnw): `mvn -s settings.xml -Dspring.profiles.active=test clean test` (Docker). Focused: `-Dtest=Class1,Class2`.
- DoD этапов: 5 — MGT-I-01, MGT-I-14; 6 — MGT-U-08 + метрики (веха M2); 7 — MGT-I-18 + все MGT-* зелёные, Swagger актуален, логи без чувствительных данных.

## Существующее для переиспользования
- `TransactionRunner` (`common/invariant/port/`) + `SpringTransactionRunner` (`common/invariant/adapter/`) — обёртка «lock/mutation/write в одной транзакции». Для аудита lock не нужен — просто `run(() -> { mutation; audit; })`. Можно перенести порт/адаптер в `common/tx` (шире, чем invariant), но это опционально; при переносе обновить импорты в invariant-сервисах.
- `LimitKind` (`limitrule/domain/`) — кортеж вида лимита; переиспользуется в effective-limits для группировки по видам.
- `ProblemEnvelope`/`ProblemDetail`/`GlobalExceptionHandler` — формат ошибок.

---

## Task 1 (этап 5a): инфраструктура аудита + operator-контекст + `GET /audit-events`

Законченный слой аудита без вплетения в мутации (это Task 2).

**Deliverable:**
- Миграция `V14__audit_event.sql`: таблица `limit_management.audit_event(id uuid pk, entity_type varchar, entity_id varchar, action varchar, actor_id varchar not null, actor_name varchar, occurred_at timestamptz not null, before jsonb, after jsonb)`; индексы по `(entity_type, entity_id, occurred_at)` и `occurred_at`. Append-only (никаких update/delete из кода).
- Домен `audit/domain/AuditEvent(UUID id, String entityType, String entityId, String action, String actorId, String actorName, Instant occurredAt, String beforeJson, String afterJson)`.
- Порт `audit/application/port/out/AuditEventRepository` (`void append(AuditEvent)`, `List<AuditEvent> find(String entityType, String entityId, Instant from, Instant to, page/size)`) + `PostgresAuditEventRepository`.
- **Operator-контекст:** порт `audit/application/OperatorContext` (Spring-free интерфейс: `String operatorId()`, `String operatorName()`); адаптер request-scoped `audit/adapter/in/web/RequestOperatorContext` (@RequestScope @Component), заполняемый фильтром/интерсептором.
- **Фильтр X-Operator-Id:** `audit/adapter/in/web/OperatorHeaderFilter` (или HandlerInterceptor) — для мутирующих HTTP-методов (POST/PATCH/PUT/DELETE) под `/internal/v1/limit-management/**` требует непустой `X-Operator-Id`; отсутствие → **400** (ProblemEnvelope, code `OPERATOR_ID_REQUIRED`), до выполнения контроллера. `X-Operator-Name` опционально. GET — не требует.
- `AuditEventService` (application) + `GET /internal/v1/limit-management/audit-events?entityType=&entityId=&from=&to=&page=&size=` → список записей (маппинг before/after как JSON-объекты в ответе).

**Interfaces (produces):** `OperatorContext`, `AuditEventRepository.append(...)`, `AuditEvent` — потребляются Task 2.

**Тесты (внутри задачи, TDD):** мутирующий запрос без `X-Operator-Id` → 400 (пока на любом существующем POST, напр. создание правила); `GET /audit-events` возвращает записи с фильтрами; append/find round-trip (Testcontainers). Синтетические actor id.

**DoD задачи:** фильтр 400 работает (частично MGT-I-14 — «изменение не применено» добьётся в Task 2, когда мутации транзакционны); `/audit-events` работает; full suite зелёный. Commit `feat: audit event storage, operator context and X-Operator-Id enforcement`.

---

## Task 2 (этап 5b): запись аудита во все мутации в той же транзакции (MGT-I-01, MGT-I-14)

Крупная кросс-срезная задача. Каждая мутирующая операция пишет `audit_event` атомарно с изменением.

**Deliverable:** обернуть тело каждого мутирующего use-case в `transactionRunner.run(() -> { ...mutation...; auditEventRepository.append(event); })`, где `event` строится из `OperatorContext` (actorId/actorName), сущности (entityType/entityId), действия (CREATE/UPDATE/ACTIVATE/DISABLE/NEW_VERSION/CLOSE/COMPILE/ROLLBACK/ASSIGN_MEMBERSHIP/…) и before/after (JSON состояния до/после; для CREATE before=null, для чтения не пишем).

Точки (все мутирующие сервисы):
- `LimitRuleService`: create/patch/activate/disable/createNewVersion; `createOperationType`/`patchOperationType`.
- `MerchantGroupService`: createType/updateType/createGroup/updateGroup/assignMembership/closeMembership.
- `LimitAssignmentService`: create/patch/disable.
- `RuntimeManifestCompiler`: compile/rollback (entityType=RUNTIME_MANIFEST, action COMPILE/ROLLBACK).

Требования:
- `TransactionRunner` оборачивает **мутацию + audit** (и, где уже есть, advisory-lock/инвариант — не ломать существующие `run(...)` из этапа 3; аудит добавляется внутрь того же lambda). Сервисы остаются Spring-free — `OperatorContext`/`AuditEventRepository`/`TransactionRunner` инжектируются через `*UseCaseConfig`.
- before/after: сериализовать доменную сущность (или её DTO) в JSON стабильно (переиспользовать существующий ObjectMapper; чувствительных данных в сущностях management нет).
- `MGT-I-14`: мутация без `X-Operator-Id` → 400 И изменение НЕ применено (фильтр отвергает до транзакции — проверить, что запись в БД не появилась).
- `MGT-I-01`: CRUD группы/членства/правила/назначения создаёт запись + `audit_event` в той же транзакции (откат мутации откатывает и аудит — проверить: если мутация падает, audit_event не появляется).

**Тесты:** MGT-I-01 (для каждого типа сущности: успешная мутация → есть строго одна audit-запись с корректными actor/action/before/after в той же транзакции); MGT-I-14 (нет X-Operator-Id → 400, ничего не записано); тест атомарности (искусственный сбой мутации → нет audit-записи). Обновить существующие контроллер/интеграционные тесты: теперь мутирующие запросы ДОЛЖНЫ слать `X-Operator-Id` (иначе 400) — добавить заголовок в тест-хелперы.

**DoD задачи:** MGT-I-01, MGT-I-14 зелёные; все существующие тесты обновлены под обязательный `X-Operator-Id`; full suite зелёный. Commit `feat: write audit events atomically for every mutating operation`.

---

## Task 3 (этап 6a): `GET /merchants/{id}/effective-limits` (MGT-U-08)

**Deliverable:** эндпоинт предпросмотра действующих лимитов мерчанта на момент `at`, по §3.5 спеки. Чистая резолюция по приоритету уровней **MERCHANT > MERCHANT_GROUP > GLOBAL** независимо по каждому виду лимита; без обращения к engine.

Алгоритм (application, напр. `effectivelimits/application/EffectiveLimitsService`):
1. Собрать кандидаты-назначения активных правил, действующие на `at`: GLOBAL-назначения; GROUP-назначения групп, где мерчант — активный член на `at`; MERCHANT-назначения самого мерчанта. (Только enabled, правило ACTIVE, период включает `at`.)
2. Сгруппировать по виду лимита (`LimitKind` из этапа 3: metric/period/limitTargetType/direction/operationTypes).
3. По каждому виду выбрать самый специфичный уровень (MERCHANT>GROUP>GLOBAL); `mode` UNLIMITED на выбранном уровне → вид не применяется (или помечается unlimited). `overrides` — перекрытые менее специфичные назначения (level, ownerId, limitValue).
4. `manifestVersion` в ответе — версия последнего скомпилированного манифеста.

Форма ответа — §3.5: `{merchantId, at, manifestVersion, limits:[{ruleCode, ruleVersion, limitType, targetType, direction, operationTypes, appliedLevel, ownerId, mode, limitValue, assignmentId, overrides:[{level, ownerId, limitValue}]}]}`. `limitType` = checkType (`metric_period`, INTERVAL без периода).

Порт `effectivelimits/application/port/out/EffectiveLimitsRepository` для чтения кандидатов (назначения+правила+членства на `at`) + Postgres-адаптер; либо переиспользовать существующие репозитории. `GET` — read-only (X-Operator-Id не требуется).

**Тесты (MGT-U-08 + integration):** unit — резолюция уровней (MERCHANT>GROUP>GLOBAL) и overrides по §3.5 на моках; integration — реальные данные (мерчант с GLOBAL+GROUP+MERCHANT назначениями разных видов) → корректные appliedLevel/overrides; UNLIMITED на мерчанте → вид не применяется; `manifestVersion` = последний манифест. Синтетические merchant id.

**DoD задачи:** MGT-U-08 зелёный; форма ответа = §3.5; full suite зелёный. Commit `feat: add merchant effective-limits resolution endpoint`.

---

## Task 4 (этап 6b): метрики §7 → веха M2

**Deliverable:** Micrometer-метрики (через существующий actuator):
- Таймер/счётчик компиляций манифеста: длительность + результат (success/conflict) — обернуть `RuntimeManifestCompiler.compile`.
- Gauge размера последнего манифеста (число правил/назначений/членств или байты canonical JSON).
- Счётчики конфликтов: 409 (`LIMIT_KIND_CONFLICT` в 3 точках, `ASSIGNMENT_CONFLICT`) и 422 (компиляция) — инкремент в `GlobalExceptionHandler` или в точках.
- Gauge «возраст последнего манифеста относительно последнего изменения конфигурации» + правило алерта «изменения не опубликованы» (метрика; сам алерт — в мониторинге, здесь — экспонировать значение).

Реализация без нарушения Spring-free application: метрики инжектировать в адаптеры (контроллеры/`GlobalExceptionHandler`) или ввести тонкий `MetricsPort` + Micrometer-адаптер, вызываемый из адаптер-слоя. `MeterRegistry` — из Spring Boot actuator.

**Тесты:** проверить регистрацию метрик и инкремент счётчиков (напр. после конфликта счётчик 409 вырос; после компиляции таймер записал sample) через `SimpleMeterRegistry`/`MeterRegistry` в тесте; актуатор-эндпоинт `/actuator/metrics` содержит имена метрик.

**DoD задачи:** метрики §7 заведены и покрыты тестами; **веха M2** — полный API для админ-панели (effective-limits + метрики) готов; full suite зелёный. Commit `feat: expose manifest and conflict metrics (M2)`.

---

## Task 5 (этап 7): сид первой группы (DRAFT) + стабилизация (MGT-I-18)

**Deliverable:**
- Data-миграция `V15__seed_first_group.sql`: создать первую группу «ПОД/ФТ выплаты» (тип группы + группа) и **DRAFT**-правила по 6 видам §2 Постановки для выплатных каналов (direction OUT): OCT (карта) и SBP_B2C (телефон). Разрез TARGET — карта и телефон раздельно (валидация 4), поэтому по счётным/суммовым видам — отдельные правила на card и phone; `AMOUNT_PER_OPERATION` (PER_OPERATION) может объединять OCT+SBP_B2C. Значения — примерные из §4 (count/day=3, count/month=24, amount/operation=600000, amount/day=600000, amount/month=1200000, interval=5 мин) как отправная точка (оператор корректирует до активации). Тексты ошибок — из §4 Постановки. Правила и групповые назначения — в статусе **DRAFT/не активны**; активация и компиляция первого манифеста — **вручную оператором** (не побочный эффект деплоя).
- Стабилизация: прогнать весь набор MGT-* (unit+integration), убедиться в зелёном CI; проверить, что Swagger/OpenAPI (если генерируется) отражает новые эндпоинты (`/audit-events`, `/effective-limits`, `/counterparty-types`); ревью логов — отсутствие чувствительных данных (в management их нет, подтвердить grep'ом на PAN-паттерны в коде/фикстурах).

**Тесты (MGT-I-18):** миграция создаёт DRAFT-сущности (правила status=DRAFT, группа существует, назначения не enabled/не активны); ни одно правило не ACTIVE после сида; компиляция манифеста сразу после сида даёт пустой/без-этих-правил манифест (они DRAFT) — активация только вручную. Integration на чистой БД: после Flyway первая группа и DRAFT-правила присутствуют, активных нет.

**DoD задачи / этапа:** MGT-I-18 зелёный; **все MGT-* зелёные**; Swagger актуален; сид только DRAFT. Commit `feat: seed first limit group in DRAFT for manual activation`.

---

## DoD (весь план)
- Этап 5: `X-Operator-Id` обязателен для мутаций (400), `audit_event` пишется в транзакции каждой мутации, `GET /audit-events` работает (MGT-I-01, MGT-I-14).
- Этап 6: `effective-limits` по §3.5 (MGT-U-08), метрики §7 → **веха M2**.
- Этап 7: сид первой группы DRAFT (MGT-I-18), все MGT-* зелёные, Swagger актуален.
- Полный прогон `mvn -s settings.xml -Dspring.profiles.active=test clean test` зелёный; контракт манифеста M1 (checksum) не изменён.

## Допущения (флагнуть пользователю при расхождении)
- Состав сид-группы (какие именно каналы/значения) — примерный, DRAFT; оператор уточняет до активации.
- Аудит покрывает и компиляцию/rollback манифеста (мутации). `X-Operator-Id` обязателен и для `POST /runtime-manifests`.
- Метрика-«алерт» экспонируется как значение; сам алертинг — в системе мониторинга (вне кода).
