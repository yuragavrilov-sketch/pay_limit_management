# Этап 4 — манифест v2 → веха M1 (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Довести runtime-манифест до схемы v2 (§4.3): `schemaVersion`, `businessTimezone`, справочник `operationTypes`, назначения всех уровней (вкл. GLOBAL — сделано), правила в полной форме (measure/limitValue/errorMessageTemplate/attributeSelector-расширение); канонизация со стабильным checksum; `If-None-Match`→304. Зафиксировать схему v2 для команды engine (**веха M1**).

**Architecture:** Гексагональная. Манифест — полный иммутабельный снимок; движок engine забирает его polling'ом. Канонизация детерминированная (отсортированные коллекции + Jackson sorted keys), checksum = SHA-256 от канонического JSON. Любое изменение алгоритма канонизации = инкремент `schemaVersion`.

**Tech Stack:** Java 21 records, Spring Boot, PostgreSQL, JdbcTemplate + Flyway, JUnit 5 + Mockito, Testcontainers, Maven.

## Global Constraints
- Русский пользователю; код/имена/комментарии английские. Без Lombok; constructor injection; JdbcTemplate; forward-only Flyway; Maven.
- Деньги строками/BigDecimal; время через `Clock`; UTC; календарные окна — в `businessTimezone` (Europe/Moscow). Синтетические идентификаторы в тестах.
- Иммутабельность манифестов; правки — новыми версиями. Fail-closed engine не ослаблять.
- Не ломать существующее: компиляция/lifecycle/rollback манифеста, инвариант этапа 3 (422), GLOBAL этапа 2.
- **Любое изменение алгоритма канонизации ⇒ инкремент `schemaVersion`** (в этом этапе фиксируем `schemaVersion=2`).
- `effectiveFrom` в прошлом → 400 (уже есть lead-time валидация; подтвердить MGT-I-10).
- Коммиты `feat:`/`fix:` после набора; без push без просьбы. Не переходить к следующей задаче с красными тестами.
- Сборка (нет mvnw): `mvn -s settings.xml -Dspring.profiles.active=test clean test` (Docker). Focused: `-Dtest=Class1,Class2`.
- DoD: MGT-I-08…13 зелёные; **веха M1** — схема v2 (вкл. attributeSelector-расширение) опубликована для engine.

## Что уже готово (не переделывать)
- `RuntimeCompiledRule` уже несёт `operationTypes`, `direction`, `attribute` (attributeSelector), `targetType`, `measure{metric,period,aggregationScope,currency,intervalMinutes}`, `limitValue`, `errorMessageTemplate` — форма правила §4.3 фактически есть.
- Назначения всех уровней (GLOBAL/MERCHANT_GROUP/MERCHANT) компилируются (этап 2).
- Повторная проверка инварианта при компиляции → 422 (этап 3).
- Канонизация: `RuntimeManifestCanonicalJson` (Jackson, sorted keys, ISO-даты); коллекции сортируются в компиляторе.

## Чего не хватает (скоуп этапа)
- `schemaVersion`, `businessTimezone`, `operationTypes[]` (код→direction,counterpartyType) в payload и снимке.
- Конфиг `businessTimezone` (default Europe/Moscow).
- Загрузчик operation-types для манифеста (порт-метод удалён на этапе 1 — вернуть).
- Колонка `schema_version` в `runtime_manifests`.
- `If-None-Match`→304 в контроллере.
- Явные тесты стабильности checksum MGT-U-06/07 над v2-payload.
- Документ схемы v2 для engine (M1).

---

## Task 1: v2-снимок — schemaVersion + businessTimezone + operationTypes

**Files:**
- Modify: `runtimeconfig/config/RuntimeManifestProperties.java` (+ businessTimezone)
- Modify: `src/main/resources/application.yml` (+ default business-timezone)
- Create: `runtimeconfig/domain/RuntimeOperationType.java`
- Modify: `runtimeconfig/domain/RuntimeManifestPayload.java` (+ schemaVersion, businessTimezone, operationTypes)
- Modify: `runtimeconfig/domain/RuntimeManifest.java` (+ schemaVersion; или пробрасывать через payload) и `adapter/in/web/RuntimeManifestResponse.java` (+ schemaVersion, businessTimezone, operationTypes)
- Modify: `runtimeconfig/application/RuntimeManifestCompiler.java` (build v2 payload; inject businessTimezone; load operationTypes)
- Modify: `runtimeconfig/application/port/out/RuntimeManifestRepository.java` (+ `listOperationTypesForManifest()`)
- Modify: `runtimeconfig/adapter/out/postgres/PostgresRuntimeManifestRepository.java` (impl loader + persist schema_version)
- Modify: `runtimeconfig/config/RuntimeManifestUseCaseConfig.java` (передать businessTimezone в компилятор)
- Create: `src/main/resources/db/migration/V12__manifest_schema_version.sql`
- Test: `RuntimeManifestCompilerTest`, `PostgresRuntimeManifestRepositoryIntegrationTest`

**Interfaces:**
- Produces:
  - `RuntimeOperationType(String code, OperationDirection direction, CounterpartyType counterpartyType)`
  - `RuntimeManifestPayload` gains leading fields `int schemaVersion` (=2), `String businessTimezone`, `List<RuntimeOperationType> operationTypes` (в дополнение к существующим).
  - `RuntimeManifestRepository.listOperationTypesForManifest()` → `List<RuntimeOperationType>` (enabled, sorted by code).
  - `RuntimeManifestResponse` exposes `schemaVersion`, `businessTimezone`, `operationTypes`.

- [ ] **Step 1: Падающий тест** — в `RuntimeManifestCompilerTest`: скомпилировать манифест, проверить `payload.schemaVersion()==2`, `payload.businessTimezone().equals("Europe/Moscow")`, `payload.operationTypes()` непустой и содержит ожидаемые (код,direction,counterpartyType). Run → FAIL.

- [ ] **Step 2: `RuntimeOperationType`** (record выше).

- [ ] **Step 3: Config** — `RuntimeManifestProperties` += `@NotNull String businessTimezone` (валидировать как корректный `ZoneId` в компиляторе/конфиге); `application.yml` → `pay-limit-management.runtime-manifest.business-timezone: Europe/Moscow`. Прокинуть в `RuntimeManifestCompiler` через `RuntimeManifestUseCaseConfig`.

- [ ] **Step 4: Порт + загрузчик** — `listOperationTypesForManifest()`:
```sql
select code, direction, counterparty_type
from limit_management.operation_types
where enabled = true
order by code asc
```
→ `RuntimeOperationType(code, OperationDirection.valueOf(direction), CounterpartyType.valueOf(counterparty_type))`.

- [ ] **Step 5: Payload v2** — добавить в `RuntimeManifestPayload` (в начало, для читаемости JSON — но помни: канонизация сортирует ключи по алфавиту, порядок полей в record на checksum не влияет) поля `int schemaVersion`, `String businessTimezone`, `List<RuntimeOperationType> operationTypes`. Обновить конструктор/копии. Обновить `RuntimeManifest` (+ `schemaVersion`, `businessTimezone`, `operationTypes` или геттеры через payload) и `RuntimeManifestResponse.from`.

- [ ] **Step 6: Компилятор** — в `buildManifest`: загрузить operationTypes, проставить `schemaVersion=2`, `businessTimezone` (из конфига). Сортировка operationTypes по code (детерминизм). Checksum считается по обновлённому payload.

- [ ] **Step 7: Миграция V12** — `alter table limit_management.runtime_manifests add column schema_version integer not null default 2;` Обновить insert заголовка в `PostgresRuntimeManifestRepository` (писать `schema_version` = payload.schemaVersion()); чтение — в mapper (если заголовок маппится). Существующие строки получают default 2 (историческая неточность допустима — прод-данных нет).

- [ ] **Step 8: Прогнать focused → GREEN**, затем full suite → GREEN. Проверить, что существующие manifest-тесты, сверяющие checksum пересчётом (`new RuntimeManifestCanonicalJson().checksum(payload)`), остаются зелёными (они пересчитывают, не хардкодят).

- [ ] **Step 9: Commit** `feat: add schemaVersion, businessTimezone and operationTypes to runtime manifest (v2)`.

---

## Task 2: канонизация v2 — стабильность checksum (MGT-U-06/07) + If-None-Match→304

**Files:**
- Modify: `runtimeconfig/adapter/in/web/RuntimeManifestController.java` (If-None-Match на GET)
- Test: `RuntimeManifestCompilerTest` / `RuntimeManifestCanonicalJson` unit (MGT-U-06/07), `RuntimeManifestControllerTest` / integration (MGT-I-13)

**Interfaces:**
- Produces: `GET /runtime-manifests/effective|active|{manifestId}` при `If-None-Match: <checksum>` совпадающем с checksum манифеста → `304 Not Modified` без тела; иначе 200 + тело + заголовок `ETag: <checksum>`.

- [ ] **Step 1: MGT-U-06/07 (unit)** — построить два payload'а v2 с одинаковым содержимым, но переупорядоченными входными коллекциями (правила/назначения/членства/operationTypes) → одинаковые canonical bytes и checksum (MGT-U-06). Изменить одно поле (напр. limitValue или businessTimezone) → другой checksum (MGT-U-07). (Компилятор сортирует коллекции; тест подаёт переупорядоченный вход через фейковый репозиторий и проверяет равенство checksum.) Run → должны пройти после сортировки; если нет — добить детерминизм.

- [ ] **Step 2: If-None-Match падающий тест (MGT-I-13)** — GET `/runtime-manifests/effective?at=...` с `If-None-Match` = текущий checksum → 304 без тела. Run → FAIL (304 не реализован).

- [ ] **Step 3: Реализация If-None-Match** — в контроллере на GET-эндпоинтах (`/effective`, `/active`, `/{manifestId}`) прочитать заголовок `If-None-Match`; если равен `manifest.checksum()` → вернуть `ResponseEntity.status(304).eTag(checksum).build()`; иначе — обычный ответ с заголовком `ETag`. Использовать `@RequestHeader(value="If-None-Match", required=false) String ifNoneMatch` и вернуть `ResponseEntity` (сменить тип с `ApiResponse<...>` на `ResponseEntity<ApiResponse<...>>` для этих методов, сохранив тело в 200). Сравнение точное по строке (checksum вида `sha256:...`); учесть возможные кавычки ETag (нормализовать).

- [ ] **Step 4: Прогнать focused → GREEN**, затем full suite → GREEN.

- [ ] **Step 5: Commit** `feat: canonical v2 checksum stability and If-None-Match 304 for runtime manifests`.

---

## Task 3: веха M1 — схема v2 для engine + подтверждение MGT-I-10

**Files:**
- Create: `docs/superpowers/specs/2026-07-07-manifest-v2-schema-M1.md` (скелет §4.3 + описание attributeSelector-расширения)
- Test: подтвердить/добавить MGT-I-10 (effectiveFrom в прошлом → 400) если не покрыт.

- [ ] **Step 1: Проверить MGT-I-10** — есть ли интеграционный тест «компиляция с effectiveFrom в прошлом → 400». Если нет — добавить (POST /runtime-manifests с effectiveFrom < now → 400 `RUNTIME_MANIFEST_LEAD_TIME_VIOLATION` или аналог). Прогнать.

- [ ] **Step 2: Документ схемы v2** — записать в `docs/.../manifest-v2-schema-M1.md`: полный JSON-скелет манифеста v2 (из спеки §4.3) **как реально эмитит код** (свериться с фактическим выводом канонизации — вставить пример реального canonical JSON из теста), с явным разделом про поле `attributeSelector` (расширение сверх §4.3, опциональное, engine должен уметь его читать/игнорировать до поддержки), `schemaVersion=2`, правило отклонения неизвестной schemaVersion (fail-closed). Указать: порядок раскатки — engine с поддержкой v2 деплоится раньше первой компиляции v2.

- [ ] **Step 3: Commit** `docs: publish runtime manifest v2 schema for engine (M1)`.

- [ ] **Step 4:** Сообщить контроллеру (оркестратору), что для полной вехи M1 нужно опубликовать этот документ в wiki (MCP outline) — это делает пользователь/оркестратор, не сабагент.

---

## Task 4: переформовка эмишна манифеста под техспеку §4.3 (веха M1 — верный контракт)

Фактический сериализуемый манифест расходится с §4.3 (обёртка `matcher{}`, `version` вместо `manifestVersion`, плоские `ownerType`/`ownerId` вместо `owner{level,id}`, `limitValue` числом). Решение пользователя: переформовать эмишн под §4.3. Вводим отдельный **v2-wire-слой** (§4.3-форма), над которым считается checksum и который сохраняется в `payload_json`/отдаётся engine. Внутренние compiled-записи, логика инварианта (422) и rollback — не трогаем (маппинг только на сериализации).

**Files:**
- Create: `runtimeconfig/domain/wire/ManifestDocumentV2.java` (+ вложенные `RuleV2`, `MeasureV2`, `AttributeSelectorV2`, `AssignmentV2`, `OwnerV2`, `MembershipV2`, `OperationTypeV2`)
- Create: `runtimeconfig/application/ManifestDocumentV2Mapper.java` (internal payload → ManifestDocumentV2)
- Modify: `runtimeconfig/application/RuntimeManifestCanonicalJson.java` (checksum над ManifestDocumentV2, а не над внутренним payload)
- Modify: `runtimeconfig/application/RuntimeManifestCompiler.java` (checksum считать по документу; строить документ)
- Modify: `runtimeconfig/adapter/out/postgres/PostgresRuntimeManifestRepository.java` (в `payload_json` писать ManifestDocumentV2)
- Modify: `runtimeconfig/adapter/in/web/RuntimeManifestResponse.java` (GET отдаёт §4.3-форму — либо возвращать ManifestDocumentV2 напрямую как `document`, либо заменить тело ответа на него)
- Modify: `docs/superpowers/specs/2026-07-07-manifest-v2-schema-M1.md` (обновить под верную §4.3-форму; убрать раздел «отклонения формы», оставить только attributeSelector-расширение)
- Test: `RuntimeManifestCanonicalJsonTest`/`RuntimeManifestCompilerTest`, `RuntimeManifestControllerTest`, integration.

**Interfaces (целевая §4.3-форма, над которой считается checksum — БЕЗ поля checksum внутри документа для хеширования; checksum добавляется в ответ отдельно):**
```
ManifestDocumentV2(
  int schemaVersion,            // 2
  int manifestVersion,          // = внутренний version
  Instant effectiveFrom,
  String businessTimezone,
  List<OperationTypeV2> operationTypes,   // {code, direction, counterpartyType}
  List<RuleV2> rules,
  List<AssignmentV2> assignments,
  List<MembershipV2> memberships
)
RuleV2(UUID ruleId, String code, int version, MeasureV2 measure, String limitValue /*toPlainString, null для INTERVAL*/,
       List<String> operationTypes, String direction, String limitTargetType /*nullable*/,
       String errorMessageTemplate, AttributeSelectorV2 attributeSelector /*расширение*/)
MeasureV2(String metric, String period /*nullable*/, String aggregationScope /*nullable*/,
          String currency /*nullable*/, Integer intervalMinutes /*nullable*/)
AttributeSelectorV2(String type, String value /*nullable*/)
AssignmentV2(UUID assignmentId, UUID ruleId, OwnerV2 owner, String mode, Instant activeFrom, Instant activeTo)
OwnerV2(String level, String id /*nullable/absent для GLOBAL*/)
MembershipV2(UUID membershipId, UUID groupId, String merchantId, Instant activeFrom, Instant activeTo)
OperationTypeV2(String code, String direction, String counterpartyType)
```
Замечания по маппингу: `manifestVersion` = внутренний `version`; assignment `validFrom/validTo` → `activeFrom/activeTo`, `limitMode` → `mode`, `ownerType/ownerId` → `owner{level,id}` (для GLOBAL `id` = null); membership `validFrom/validTo` → `activeFrom/activeTo`, `groupTypeId` НЕ выводится; `limitValue` — строка через `toPlainString()` (для INTERVAL — null); measure включает currency/intervalMinutes когда заданы (скелет §4.3 показывал только COUNT-пример). Коллекции уже отсортированы компилятором — сохранить порядок при маппинге (детерминизм checksum).

**Interfaces (что меняется):** checksum теперь SHA-256 от канонического JSON `ManifestDocumentV2` (не внутреннего payload). Значение изменится относительно Task 1/2 — это допустимо, v2 ещё не потреблялась engine; `schemaVersion` остаётся 2 (единая v2-схема, финализируем форму до публикации M1).

- [ ] **Step 1: Падающий тест формы** — интеграционный/юнит: скомпилировать манифест, сериализовать документ, проверить §4.3-форму: `manifestVersion` присутствует (нет top-level `version`), правило имеет плоские `operationTypes`/`direction`/`limitTargetType`/`measure`/`limitValue`(строка)/`errorMessageTemplate`/`attributeSelector` (нет `matcher`), назначение имеет `owner{level,id}`/`mode`/`activeFrom`, GLOBAL-назначение → `owner.level=GLOBAL`, `owner.id` отсутствует/null, membership имеет `activeFrom` и не имеет `groupTypeId`, `limitValue` для AMOUNT-правила — строка. Run → FAIL.

- [ ] **Step 2: Wire-DTO** `ManifestDocumentV2` + вложенные records (выше).

- [ ] **Step 3: Маппер** `ManifestDocumentV2Mapper.toDocument(RuntimeManifestPayload)` (или из `RuntimeManifest`) — детерминированный, сохраняет порядок коллекций.

- [ ] **Step 4: Канонизация + checksum над документом** — `RuntimeManifestCanonicalJson.checksum(ManifestDocumentV2)`/`bytes(...)`; компилятор строит документ и считает по нему checksum. `payload_json` = сериализованный документ. Убедиться: Jackson sorted keys, ISO-даты, `NON_NULL`/явные null — согласовать (для стабильности лучше сериализовать null явно; выбрать и зафиксировать поведение, покрыть тестом).

- [ ] **Step 5: GET-ответ** отдаёт §4.3-документ (engine читает именно его). Обновить `RuntimeManifestResponse` (либо вернуть `ManifestDocumentV2` + метаданные lifecycle + checksum). `If-None-Match`/304 и ETag сохранить (Task 2).

- [ ] **Step 6: MGT-U-06/07 переобновить** под checksum-по-документу (стабильность при переупорядоченном входе; отличие при смене поля) — должны остаться зелёными.

- [ ] **Step 7: Обновить M1-документ** `manifest-v2-schema-M1.md` под верную §4.3-форму: убрать «отклонения формы», оставить `attributeSelector` как единственное расширение сверх §4.3; вставить реальный canonical JSON из теста.

- [ ] **Step 8: full suite → GREEN. Commit** `refactor: emit runtime manifest in spec §4.3 shape (v2 wire contract for engine)`.

---

## DoD этапа 4
- MGT-I-08 (состав снимка §4.1, checksum сходится), I-10 (effectiveFrom в прошлом → 400), I-11 (пустая конфигурация → валидный манифест), I-12 (rollback), I-13 (If-None-Match → 304) зелёные; MGT-U-06/07 (стабильность checksum) зелёные.
- Payload содержит `schemaVersion=2`, `businessTimezone`, `operationTypes`, правила полной формы (вкл. attributeSelector), назначения всех уровней.
- Полный прогон `mvn -s settings.xml -Dspring.profiles.active=test clean test` зелёный.
- **Веха M1:** схема v2 задокументирована (repo); публикация в wiki — задача оркестратора/пользователя.

## После M1 (вне критического пути)
Этап 5 (аудит X-Operator-Id/audit_event), этап 6 (effective-limits + метрики → веха M2), этап 7 (сид первой группы).
