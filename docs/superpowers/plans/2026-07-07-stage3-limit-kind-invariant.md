# Этап 3 — инвариант непересечения видов лимитов (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Не допустить, чтобы мерчант получал два конфликтующих групповых вида лимита из разных групп — проверкой в трёх точках (членство, групповое назначение, активация правила) под advisory lock, с единым DTO `conflicts` (409) и повторной проверкой при компиляции манифеста (422).

**Architecture:** Новый общий доменный тип `LimitKind` (кортеж §2.1) с функцией конфликта; application-сервис `LimitKindInvariantChecker` с outbound-портом `LimitKindInvariantRepository`, инжектируемый в три существующих сервиса (`MerchantGroupService`, `LimitAssignmentService`, `LimitRuleService`) и в компилятор runtime-манифеста. Advisory lock — `pg_advisory_xact_lock` в той же транзакции, что и проверка+запись.

**Tech Stack:** Java 21 records, Spring Boot, PostgreSQL (`limit_management`), JdbcTemplate + Flyway, JUnit 5 + Mockito, Testcontainers, Maven.

## Global Constraints

- Отвечать пользователю на русском; код/имена/комментарии — на английском.
- Инвариант **НЕ** выражается констрейнтом БД (три таблицы) — только application-проверка под advisory lock + повторная проверка при компиляции. Не делать триггером.
- Advisory lock: по `merchant_id` (для членств), по `rule` (для назначений и активации) — `pg_advisory_xact_lock` внутри транзакции проверки+записи.
- Кортеж «вида лимита» = (тип контроля=(metric,period), limitTargetType, direction, множество operationTypes). Конфликт = совпадение (metric, period, limitTargetType, direction) **И** пересечение множеств operationTypes.
- Инвариант scoped на **групповой** уровень: учитываются только назначения `owner_level=MERCHANT_GROUP` активных правил (enabled, не-disabled) и членства (current + future). MERCHANT/GLOBAL-уровни в инвариант не входят (это иерархия переопределения).
- Единый DTO `conflicts` (§3.4 спеки) одинаков во всех трёх точках: `[{merchantId, limitKind{checkType, targetType, direction, operationTypes}, existingGroupId, requestedGroupId}]`, код `LIMIT_KIND_CONFLICT`, HTTP 409 (в трёх точках API) / 422 (при компиляции манифеста).
- Деньги строками/BigDecimal; время через `Clock`; хранение UTC.
- Иммутабельность манифестов/журналов; без Lombok; constructor injection; JdbcTemplate (без JPA); только Maven; forward-only Flyway.
- Не ломать существующее поведение (CRUD, lifecycle, GiST-периоды, модель правила этапа 1). Fail-closed engine не ослаблять.
- Коммиты `feat:`/`fix:`/`refactor:` после каждого набора; **без push** без явной просьбы.
- Не переходить к следующей задаче с красными тестами. DoD этапа — MGT-I-02…07 (вкл. конкурентный MGT-I-06) зелёные.
- Сборка/тесты (нет mvnw): `mvn -s settings.xml -Dspring.profiles.active=test test` (Docker для Testcontainers). Focused: `-Dtest=Class1,Class2`.

## Семантика проверки (крайне важно — фиксирует поведение MGT-I-02…07)

Обозначения: «вид лимита группы G» = множество `LimitKind` по активным правилам, назначенным G групповым (enabled) назначением. «Виды, получаемые мерчантом M» = объединение видов всех групп, где M — активный/будущий член.

Три точки:
- **(а) Добавление членства** M → группа G (`assignMembership`): p[под advisory lock по merchant_id] вычислить виды G; для каждого вида G проверить, нет ли конфликтующего вида, уже получаемого M из **другой** группы. Конфликт → 409 `LIMIT_KIND_CONFLICT` с `requestedGroupId=G`, `existingGroupId=другая группа`. (MGT-I-03; непересекающиеся — MGT-I-07.)
- **(б) Добавление группового назначения** правила R группе G (`createAssignment`, только `owner_level=MERCHANT_GROUP`): [под advisory lock по rule R] вид K правила R; для каждого члена M группы G проверить K против видов, получаемых M из **других** групп (≠G). Конфликт → 409. (MGT-I-04.)
- **(в) Активация правила** R (`activateRule`): [под advisory lock по rule R] для каждой группы G, где R назначено enabled-групповым назначением, вид K правила R (после активации); для каждого члена M группы G проверить против других групп. Конфликт → 409. (MGT-I-05.)
- **Повторная проверка при компиляции** (§4.2 шаг 3): по всему снимку — конфликтов быть не должно; нарушение → 422 (третья линия защиты). (Задача 5.)
- Конкурентность (MGT-I-06): два изменения, каждое по отдельности валидное, вместе нарушают инвариант → advisory lock сериализует, одно 200, второе 409.

## Файловая структура

Новое:
- `limitrule/domain/LimitKind.java` — кортеж + `of(LimitRule)` + `conflictsWith`.
- `common/invariant/LimitKindConflict.java` — DTO элемента conflicts.
- `common/invariant/LimitKindConflictException.java` — исключение с `List<LimitKindConflict>` + флаг статуса (409/422).
- `common/invariant/LimitKindInvariantChecker.java` — application-сервис проверки.
- `common/invariant/port/LimitKindInvariantRepository.java` — outbound-порт.
- `common/invariant/adapter/PostgresLimitKindInvariantRepository.java` — реализация (advisory lock + запросы).
- `common/invariant/config/LimitKindInvariantConfig.java` — @Configuration бина checker.
- тесты: `LimitKindTest`, `LimitKindInvariantCheckerTest` (unit, Mockito), интеграционные MGT-I-03/04/05/06/07.

Меняется:
- `merchantgroup/application/MerchantGroupService.java` (+ checker в `assignMembership`).
- `limitassignment/application/LimitAssignmentService.java` (+ checker в `createAssignment` для MERCHANT_GROUP).
- `limitrule/application/LimitRuleService.java` (+ checker в `activateRule`).
- их `*UseCaseConfig` (прокинуть бин checker).
- `runtimeconfig/application/RuntimeManifestCompiler.java` (+ повторная проверка → 422).
- `common/web/GlobalExceptionHandler.java` (+ обработчик `LimitKindConflictException` → 409/422 с блоком conflicts).

---

## Task 1: доменный тип LimitKind + конфликт

Чистая доменная логика, unit-тесты, без Spring/БД.

**Files:**
- Create: `src/main/java/ru/copperside/paylimits/management/limitrule/domain/LimitKind.java`
- Test: `src/test/java/ru/copperside/paylimits/management/limitrule/domain/LimitKindTest.java`

**Interfaces:**
- Produces:
  - `LimitKind(RuleMetric metric, RulePeriod period, LimitTargetType limitTargetType, OperationDirection direction, java.util.Set<String> operationTypes)`
    - `static LimitKind of(LimitRule rule)`
    - `boolean conflictsWith(LimitKind other)` — true ⇔ `metric==other.metric && java.util.Objects.equals(period, other.period) && Objects.equals(limitTargetType, other.limitTargetType) && direction==other.direction && !Collections.disjoint(operationTypes, other.operationTypes)`

- [ ] **Step 1: Падающий unit-тест**

```java
class LimitKindTest {
    @Test
    void conflictsWhenSameCheckTypeTargetDirectionAndOperationTypesIntersect() {
        LimitKind a = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT"));
        LimitKind b = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT", "AFT"));
        assertThat(a.conflictsWith(b)).isTrue();
    }

    @Test
    void doesNotConflictWhenOperationTypesDisjoint() {
        LimitKind a = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT"));
        LimitKind b = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("AFT"));
        assertThat(a.conflictsWith(b)).isFalse();
    }

    @Test
    void doesNotConflictWhenTargetTypeDiffers() {
        LimitKind a = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT"));
        LimitKind b = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.OUT, Set.of("OCT"));
        assertThat(a.conflictsWith(b)).isFalse();
    }

    @Test
    void ofDerivesKindFromRule() {
        LimitRule rule = /* build a COUNT/DAY/CARD/OUT rule over {OCT} using the Task-2 constructor */ null;
        // replace null with a real LimitRule; assert LimitKind.of(rule) has matching fields
    }
}
```
(Собрать `LimitRule` через конструктор этапа 1; посмотреть пример в `RuleValidationTest`.)

- [ ] **Step 2: Прогнать → RED**

Run: `mvn -s settings.xml -q -Dspring.profiles.active=test -Dtest=LimitKindTest test`  Expected: FAIL (нет `LimitKind`).

- [ ] **Step 3: Реализовать `LimitKind`**

```java
package ru.copperside.paylimits.management.limitrule.domain;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

public record LimitKind(
        RuleMetric metric,
        RulePeriod period,
        LimitTargetType limitTargetType,
        OperationDirection direction,
        Set<String> operationTypes
) {
    public LimitKind {
        operationTypes = operationTypes == null ? Set.of() : Set.copyOf(operationTypes);
    }

    public static LimitKind of(LimitRule rule) {
        return new LimitKind(
                rule.measure().metric(),
                rule.measure().period(),
                rule.limitTargetType(),
                rule.direction(),
                rule.operationTypes());
    }

    public boolean conflictsWith(LimitKind other) {
        return metric == other.metric
                && Objects.equals(period, other.period)
                && Objects.equals(limitTargetType, other.limitTargetType)
                && direction == other.direction
                && !Collections.disjoint(operationTypes, other.operationTypes);
    }
}
```

- [ ] **Step 4: Прогнать → GREEN**

Run: same as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add limit-kind tuple with conflict detection"
```

---

## Task 2: conflicts DTO + исключение + обработчик

**Files:**
- Create: `common/invariant/LimitKindConflict.java`, `common/invariant/LimitKindConflictException.java`
- Modify: `common/web/GlobalExceptionHandler.java`
- Test: `common/web/` — MockMvc-тест (или добавить в существующий), проверяющий сериализацию 409 с блоком `conflicts`.

**Interfaces:**
- Produces:
  - `LimitKindConflict(String merchantId, LimitKindView limitKind, UUID existingGroupId, UUID requestedGroupId)` где `LimitKindView(String checkType, String targetType, String direction, List<String> operationTypes)` (checkType = `metric + "_" + period`, напр. `COUNT_DAY`; для INTERVAL — `INTERVAL`).
  - `LimitKindConflictException extends RuntimeException` с `List<LimitKindConflict> conflicts()` и `boolean compilation()` (false → 409, true → 422).

- [ ] **Step 1: Реализовать DTO + исключение** (значения строками; `LimitKindView.of(LimitKind, merchant/group ids)` хелпер для маппинга; checkType по правилу «metric_period», INTERVAL без period).

- [ ] **Step 2: Обработчик в `GlobalExceptionHandler`**

```java
@ExceptionHandler(LimitKindConflictException.class)
ResponseEntity<ProblemEnvelope> handleLimitKindConflict(LimitKindConflictException ex) {
    HttpStatus status = ex.compilation() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT;
    return problem(status, "LIMIT_KIND_CONFLICT",
            "Merchant already receives a conflicting limit kind from another group",
            ex.getMessage(), ex.conflicts());
}
```
(Блок `conflicts` кладётся в `details` ProblemDetail; проверить, что `ProblemDetail.details` сериализуется как `conflicts` в теле — при необходимости добавить поле `conflicts` в ProblemEnvelope/ProblemDetail отдельно от `details`, чтобы соответствовать §3.4 ровно. Решение: добавить в `ProblemDetail` необязательное поле `conflicts` и заполнять его здесь; остальные ошибки оставляют его null.)

- [ ] **Step 3: MockMvc-тест** сериализации: сматченный 409 содержит `code=LIMIT_KIND_CONFLICT` и непустой `conflicts[0].limitKind.checkType`.

- [ ] **Step 4: Прогнать focused → GREEN. Commit** `feat: add limit-kind conflict dto and error mapping`.

---

## Task 3: outbound-порт + Postgres-реализация (advisory lock + запросы)

**Files:**
- Create: `common/invariant/port/LimitKindInvariantRepository.java`, `common/invariant/adapter/PostgresLimitKindInvariantRepository.java`
- Test: `common/invariant/adapter/PostgresLimitKindInvariantRepositoryIntegrationTest.java` (Testcontainers)

**Interfaces:**
- Produces port `LimitKindInvariantRepository`:
  - `void lockMerchant(String merchantId)` — `pg_advisory_xact_lock(hashtext(?))`.
  - `void lockRule(UUID ruleId)` — `pg_advisory_xact_lock(hashtext(?))` по ruleId.toString().
  - `List<LimitKind> kindsDeliveredByGroup(UUID groupId)` — по enabled MERCHANT_GROUP-назначениям активных правил группы.
  - `List<String> membersOfGroup(UUID groupId)` — merchant_id членств группы, актуальных или будущих (`valid_to is null or valid_to > now`), distinct.
  - `List<MerchantGroupKind> kindsReceivedByMerchantExcludingGroup(String merchantId, UUID excludedGroupId)` — для каждой группы (кроме excluded), где merchant активный/будущий член, вид+groupId. `MerchantGroupKind(UUID groupId, LimitKind kind)`.
  - `List<UUID> groupsWithEnabledAssignmentForRule(UUID ruleId)` — группы с enabled MERCHANT_GROUP-назначением этого правила.

- [ ] **Step 1: Порт-интерфейс** (методы выше; advisory-lock методы должны вызываться внутри существующей транзакции проверки).

- [ ] **Step 2: Реализация** — SQL. Пример `kindsDeliveredByGroup`:
```sql
select r.id, r.metric, r.period, r.target_type, r.direction
from limit_management.limit_assignments a
join limit_management.limit_rules r on r.id = a.rule_id
where a.owner_type = 'MERCHANT_GROUP' and a.owner_id = ? and a.enabled = true
  and r.status = 'ACTIVE'
```
Для каждого правила подтянуть operationTypes из junction (или один запрос с `array_agg`), собрать `LimitKind`. `membersOfGroup`: `select distinct merchant_id from merchant_group_memberships where group_id = ? and (valid_to is null or valid_to > now())`. `kindsReceivedByMerchantExcludingGroup`: соединить memberships (актуальные/будущие) мерчанта → группы (≠excluded) → их enabled MERCHANT_GROUP-назначения активных правил → kinds. Advisory lock: `jdbcTemplate.update("select pg_advisory_xact_lock(hashtext(?))", key)`.

- [ ] **Step 3: Integration-тест** (Testcontainers): наполнить группы/правила/назначения/членства, проверить что каждый метод возвращает ожидаемые kinds; advisory-lock метод не бросает.

- [ ] **Step 4: focused → GREEN. Commit** `feat: add limit-kind invariant repository with advisory locking`.

---

## Task 4: LimitKindInvariantChecker + интеграция в три точки

**Files:**
- Create: `common/invariant/LimitKindInvariantChecker.java`, `common/invariant/config/LimitKindInvariantConfig.java`
- Modify: `MerchantGroupService`, `LimitAssignmentService`, `LimitRuleService` + их `*UseCaseConfig`
- Test: `LimitKindInvariantCheckerTest` (Mockito) + интеграционные MGT-I-03/04/05/07

**Interfaces:**
- Produces `LimitKindInvariantChecker`:
  - `void checkMembership(String merchantId, UUID requestedGroupId)` — точка (а): lockMerchant → kindsDeliveredByGroup(G) × kindsReceivedByMerchantExcludingGroup(M,G) → конфликты → throw.
  - `void checkGroupAssignment(UUID ruleId, UUID groupId)` — точка (б): lockRule → kind правила × членов G × их другие группы → throw.
  - `void checkRuleActivation(UUID ruleId)` — точка (в): lockRule → groupsWithEnabledAssignmentForRule(R) → для каждой G как (б).
  - Каждый бросает `LimitKindConflictException(conflicts, compilation=false)` при непустом наборе.
- Consumes порт (Task 3), `LimitKind`/`LimitRule` (нужно уметь получить `LimitKind` правила — добавить в порт `Optional<LimitKind> kindOfRule(UUID ruleId)` или прокинуть правило).

- [ ] **Step 1: Unit-тесты checker** (Mockito-мок порта): membership-конфликт бросает с правильными merchantId/existing/requested; disjoint — не бросает; assignment/activation аналогично. (MGT-I-03/04/05 в unit-форме + happy path.)
- [ ] **Step 2: Реализовать checker** (композиция запросов порта + `conflictsWith`; собрать `LimitKindConflict` с merchantId и парой groupId). RED→GREEN на unit.
- [ ] **Step 3: Встроить в сервисы** — вызвать соответствующий метод checker в `assignMembership` (перед save, внутри транзакции с lock), `createAssignment` (только если `ownerType==MERCHANT_GROUP`), `activateRule` (перед сменой статуса на ACTIVE). Прокинуть бин через `*UseCaseConfig` (checker — новый параметр конструктора сервисов). Сервисы остаются Spring-free; транзакция+lock — в adapter/checker-обвязке (пометить методы репозитория/или обёртку `@Transactional`, чтобы advisory lock жил в той же транзакции, что и последующая запись сервиса — согласовать границу; при необходимости ввести транзакционную обёртку в adapter-слое).
- [ ] **Step 4: Интеграционные MGT-I-03/04/05/07** (Testcontainers, MockMvc): 03 — членство с пересекающимся видом → 409+conflicts; 04 — назначение группе конфликтующего вида → 409; 05 — активация правила, создающего конфликт → 409; 07 — членство во вторую группу без пересечения → 201.
- [ ] **Step 5: full suite → GREEN. Commit** `feat: enforce limit-kind non-overlap invariant at membership, assignment, activation`.

---

## Task 5: повторная проверка при компиляции (422) + конкурентный MGT-I-06

**Files:**
- Modify: `runtimeconfig/application/RuntimeManifestCompiler.java` (+ проверка инварианта по снимку → `LimitKindConflictException(compilation=true)`)
- Test: интеграционный тест компиляции с искусственно нарушенным снимком → 422; конкурентный MGT-I-06.

- [ ] **Step 1: Проверка по снимку** — на шаге компиляции пройтись по членствам+групповым назначениям снимка, собрать для каждого мерчанта его виды из разных групп, при конфликте бросить `LimitKindConflictException(conflicts, true)`. (Переиспользовать `LimitKind.conflictsWith`; можно вынести чистую функцию проверки набора в `LimitKindInvariantChecker` как `checkSnapshot(...)`.)
- [ ] **Step 2: Тест 422** при нарушенном снимке (вставить конфликтующую конфигурацию в обход API-проверок, затем компиляция → 422).
- [ ] **Step 3: Конкурентный MGT-I-06** — два потока, каждое изменение по отдельности валидно, вместе нарушают; ожидание: одно 200/201, второе 409. Использовать реальный пул соединений (Testcontainers), синхронизацию потоков; проверить, что advisory lock сериализует.
- [ ] **Step 4: full suite → GREEN. Commit** `feat: re-check limit-kind invariant during manifest compilation and cover concurrency`.

---

## DoD этапа 3

- MGT-I-02 (GiST периода — уже есть), MGT-I-03, I-04, I-05, I-06 (конкурентный), I-07 зелёные.
- Единый блок `conflicts` (§3.4) в трёх точках (409) и при компиляции (422).
- Advisory lock по merchant_id / rule; инвариант не выражен констрейнтом БД.
- Полный прогон `mvn -s settings.xml -Dspring.profiles.active=test test` зелёный.
- Существующее поведение (CRUD/lifecycle/GiST/модель правила) не сломано.

## Следующий этап

**Этап 4** — манифест v2 (`schemaVersion=2`, `businessTimezone`, operationTypes в снимке, attributeSelector-расширение, `If-None-Match`→304, канонизация + стабильный checksum MGT-U-06/07) → **веха M1**.
