# Этап 2 — уровень назначений GLOBAL (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Добавить третий уровень владельца назначений — GLOBAL (owner_id отсутствует) — в домен, БД, API и компиляцию манифеста, не сломав существующие MERCHANT_GROUP/MERCHANT и инвариант непересечения.

**Architecture:** Гексагональная. GLOBAL — назначение с `ownerType=GLOBAL`, `ownerId=null`. Инвариант непересечения видов лимитов на GLOBAL НЕ распространяется (он про групповой уровень) — существующий guard `ownerType==MERCHANT_GROUP` уже это обеспечивает.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL, JdbcTemplate + Flyway, JUnit 5 + Mockito, Testcontainers, Maven.

## Global Constraints
- Русский пользователю; код/имена/комментарии английские. Без Lombok; constructor injection; JdbcTemplate; forward-only Flyway; только Maven.
- Деньги строками/BigDecimal; время через `Clock`; UTC; синтетические идентификаторы в тестах (без PAN/телефона/счёта).
- Иммутабельность манифестов/журналов. Fail-closed не ослаблять.
- Не ломать существующее: MERCHANT_GROUP/MERCHANT назначения, GiST-периоды, инвариант этапа 3, модель этапа 1.
- Инвариант непересечения на GLOBAL не распространяется (только групповой уровень).
- Коммиты `feat:`/`fix:` после набора; без push без просьбы. Не переходить к следующей задаче с красными тестами.
- Сборка/тесты (нет mvnw): `mvn -s settings.xml -Dspring.profiles.active=test test` (Docker). Focused: `-Dtest=Class1,Class2`.
- DoD: MGT-I-17 (создание GLOBAL, фильтрация, вхождение в манифест) зелёный; существующие тесты зелёные.

## Ловушки
- `owner_id` становится NULLable → GiST exclusion `owner_id with =` НЕ поймает два GLOBAL-назначения одного правила (NULL≠NULL). Использовать `coalesce(owner_id,'') with =`.
- `hasEnabledOverlap` использует `owner_id = ?` → для GLOBAL (NULL) не сработает; использовать `coalesce(owner_id,'') = coalesce(?, '')`.
- Компилятор манифеста сортирует `.thenComparing(RuntimeCompiledAssignment::ownerId)` → NPE на null ownerId; сделать null-safe.
- CHECK `owner_id_not_blank` должен допускать NULL.

---

## Task 1: уровень GLOBAL в домене, БД и API (без манифеста)

**Files:**
- Modify: `limitassignment/domain/AssignmentOwnerType.java` (+ GLOBAL)
- Modify: `limitassignment/application/LimitAssignmentService.java` (validateOwner GLOBAL)
- Modify: `limitassignment/adapter/in/web/LimitAssignmentController.java` (ownerId optional)
- Modify: `limitassignment/adapter/out/postgres/PostgresLimitAssignmentRepository.java` (hasEnabledOverlap coalesce)
- Create: `src/main/resources/db/migration/V11__global_assignment_level.sql`
- Test: `LimitAssignmentServiceTest`, `LimitAssignmentControllerTest`, `PostgresLimitAssignmentRepositoryIntegrationTest`

**Interfaces:**
- Produces: `AssignmentOwnerType { GLOBAL, MERCHANT_GROUP, MERCHANT }`; GLOBAL assignment persisted with `owner_id = null`; `POST /assignments` with `owner_type=GLOBAL` and no `ownerId` → 201; providing ownerId for GLOBAL → 400; GLOBAL assignment appears in `GET /assignments`.

- [ ] **Step 1: Падающий integration-тест** — в `PostgresLimitAssignmentRepositoryIntegrationTest` создать GLOBAL-назначение (ownerId null) активного правила, прочитать назад, проверить `ownerType=GLOBAL`, `ownerId=null`; второй перекрывающийся по периоду GLOBAL того же правила → нарушение GiST (`ASSIGNMENT_CONFLICT`). Run `-Dtest=PostgresLimitAssignmentRepositoryIntegrationTest` → FAIL (GLOBAL нет).

- [ ] **Step 2: Enum** — `AssignmentOwnerType { GLOBAL, MERCHANT_GROUP, MERCHANT }`.

- [ ] **Step 3: Миграция V11**
```sql
alter table limit_management.limit_assignments
    drop constraint limit_assignments_owner_type_chk,
    drop constraint limit_assignments_owner_id_not_blank,
    alter column owner_id drop not null,
    add constraint limit_assignments_owner_type_chk
        check (owner_type in ('GLOBAL', 'MERCHANT_GROUP', 'MERCHANT')),
    add constraint limit_assignments_owner_id_shape check (
        (owner_type = 'GLOBAL' and owner_id is null)
        or (owner_type in ('MERCHANT_GROUP', 'MERCHANT') and owner_id is not null and length(trim(owner_id)) > 0)
    );

-- Recreate the enabled no-overlap exclusion so GLOBAL rows (owner_id NULL) compare equal.
alter table limit_management.limit_assignments
    drop constraint limit_assignments_enabled_no_overlap;
alter table limit_management.limit_assignments
    add constraint limit_assignments_enabled_no_overlap
    exclude using gist (
        rule_id with =,
        owner_type with =,
        (coalesce(owner_id, '')) with =,
        tstzrange(valid_from, coalesce(valid_to, 'infinity'::timestamptz), '[)') with &&
    )
    where (enabled = true);
```
(GiST по выражению `coalesce(owner_id,'')` требует расширения btree_gist — оно уже используется существующим exclusion, значит доступно.)

- [ ] **Step 4: `validateOwner` в сервисе** — добавить GLOBAL-ветку:
```java
private String validateOwner(AssignmentOwnerType ownerType, String ownerId) {
    if (ownerType == AssignmentOwnerType.GLOBAL) {
        if (ownerId != null && !ownerId.isBlank()) {
            throw problem("VALIDATION_ERROR", "ownerId must be absent for GLOBAL assignments");
        }
        return null;
    }
    String normalized = requireText(ownerId, "ownerId");
    // ... existing MERCHANT_GROUP (UUID + group lookup) and MERCHANT branches unchanged ...
}
```
Убедиться, что `createAssignment` корректно передаёт null ownerId дальше; инвариант-guard `ownerType==MERCHANT_GROUP` (этап 3) уже пропускает GLOBAL.

- [ ] **Step 5: `hasEnabledOverlap`** — заменить `and owner_id = ?` на `and coalesce(owner_id, '') = coalesce(?, '')`; передавать ownerId (может быть null). Проверить порядок биндов.

- [ ] **Step 6: Контроллер** — в `CreateAssignmentRequest` убрать `@NotBlank` с `ownerId` (сделать необязательным `String ownerId`); валидация формы — в сервисе (Step 4).

- [ ] **Step 7: Unit-тест сервиса** — GLOBAL с ownerId → 400; GLOBAL без ownerId → создаётся (ownerId null). Обновить существующие тесты при необходимости.

- [ ] **Step 8: Контроллер-тест** — `POST /assignments` GLOBAL без ownerId → 201; фильтрация/список содержит GLOBAL.

- [ ] **Step 9: Прогнать focused → GREEN**, затем full suite → GREEN.

- [ ] **Step 10: Commit** `feat: add GLOBAL assignment owner level`.

---

## Task 2: GLOBAL в компиляции манифеста + MGT-I-17

**Files:**
- Modify: `runtimeconfig/application/RuntimeManifestCompiler.java` (null-safe ownerId comparator)
- Modify (при необходимости): `runtimeconfig/adapter/out/postgres/PostgresRuntimeManifestRepository.java` (чтение назначений с null owner_id — вероятно уже ок через `rs.getString`)
- Test: `RuntimeManifestCompilerTest` / `PostgresRuntimeManifestRepositoryIntegrationTest` (GLOBAL-назначение попадает в манифест), + MGT-I-17 сводный.

**Interfaces:**
- Consumes: `AssignmentOwnerType.GLOBAL`, GLOBAL assignments persisted (Task 1).
- Produces: компиляция манифеста включает enabled GLOBAL-назначения; сортировка стабильна при null ownerId.

- [ ] **Step 1: Падающий тест** — интеграционный: создать enabled GLOBAL-назначение активного правила, скомпилировать манифест, проверить, что назначение с `ownerType=GLOBAL`, `ownerId=null` присутствует в `assignments` манифеста. Run → FAIL (NPE в компараторе на null ownerId, либо назначение отсутствует).

- [ ] **Step 2: Null-safe компаратор** в `RuntimeManifestCompiler.buildManifest` (сортировка assignments):
```java
.thenComparing(RuntimeCompiledAssignment::ownerId,
        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
```
(и в любом другом месте, где `ownerId` участвует в сравнении/каноникализации). Убедиться, что чтение `listEnabledAssignmentsForCompilation` возвращает GLOBAL (нет фильтра по owner_type, который бы их отсекал).

- [ ] **Step 3: Прогнать → GREEN.**

- [ ] **Step 4: MGT-I-17** — сводный интеграционный тест: (1) создать GLOBAL-назначение → 201; (2) `GET /assignments` фильтрует/содержит GLOBAL; (3) компиляция манифеста включает его. Все три шага зелёные.

- [ ] **Step 5: full suite → GREEN. Commit** `feat: include GLOBAL assignments in runtime manifest compilation`.

---

## DoD этапа 2
- MGT-I-17 зелёный (создание GLOBAL, фильтрация, вхождение в манифест).
- Существующие MERCHANT_GROUP/MERCHANT, GiST-периоды, инвариант этапа 3 не сломаны; полный прогон зелёный.
- GLOBAL не участвует в инварианте непересечения (проверить, что создание GLOBAL-назначения конфликтующего вида НЕ даёт 409).

## Следующий этап
**Этап 4** — манифест v2 (`schemaVersion=2`, `businessTimezone`, `operationTypes` в снимке, назначения всех уровней вкл. GLOBAL, attributeSelector-расширение, `If-None-Match`→304, стабильный checksum MGT-U-06/07) → веха M1.
