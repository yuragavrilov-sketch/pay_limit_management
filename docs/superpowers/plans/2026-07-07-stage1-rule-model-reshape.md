# Этап 1 — переформовка модели правила под §2.1 (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Привести доменную модель правила лимита в соответствие со спекой §2.1 (operationTypes-множество, measure, aggregationScope, INTERVAL/PER_OPERATION, ACCOUNT, limitValue на правиле, errorMessageTemplate), сохранив attributeSelector как расширение, и закрыть кейсы MGT-U-01…05.

**Architecture:** Гексагональная (`domain` → `application` без Spring → `port/out` → `adapter`). JdbcTemplate + Flyway (без JPA/Lombok), records/sealed, constructor injection. Смена типа `LimitRule` каскадит на репозиторий, контроллер и оба компилятора манифеста — поэтому переформовка модели выполняется одной когерентной задачей (Task 2), а валидации и переезд `limitValue` — поверх неё.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL (схема `limit_management`), Flyway, JUnit 5 + Mockito, Testcontainers (PostgreSQL) + MockMvc, Maven.

## Global Constraints

- Отвечать пользователю на русском; код, имена, комментарии — на английском.
- Деньги — строками/BigDecimal; время — через инжектируемый `Clock`; хранение в UTC.
- PAN/телефон/счёт — нигде (ни в тестах, ни в логах): только HMAC + маска. В фикстурах — синтетические идентификаторы.
- Иммутабельность манифестов/журналов; правки — новыми версиями/записями.
- Записи в БД — только через существующие репозитории; миграции — только Flyway, forward-only.
- Без Lombok; constructor injection; JdbcTemplate (без JPA).
- Коммиты `feat:`/`fix:`/`refactor:` после каждого завершённого набора; **без push** без явной просьбы.
- Не переходить к следующей задаче с красными тестами (DoD этапа — все MGT-U-01…05 + обновлённые integration зелёные).
- Не ломать существующие CRUD/lifecycle групп-членств-назначений и GiST-инварианты периодов (кроме оговорённого переезда `limitValue`).
- Прогонять тесты в тестовом профиле без Vault: `./mvnw -q -Dspring.profiles.active=test test` (Testcontainers сам поднимает PostgreSQL).

## Файловая структура (что создаётся / меняется)

Новое:
- `domain/CounterpartyType.java`, `domain/AggregationScope.java`, `domain/Measure.java`
- `db/migration/V7__operation_type_counterparty.sql`
- `db/migration/V8__limit_rule_reshape.sql`
- `db/migration/V9__drop_assignment_limit_value.sql`
- тесты: `limitrule/application/RuleValidationTest.java` (MGT-U-01…05)

Меняется (по задачам):
- `domain/OperationType.java`, `domain/LimitRule.java`, `domain/RuleMetric.java`, `domain/RulePeriod.java`, `domain/LimitTargetType.java`, `domain/OperationDirection.java`, `domain/RuleDictionaries.java`, `domain/CompiledRule.java`
- `application/LimitRuleService.java`, `application/CreateLimitRuleCommand.java`, `application/PatchLimitRuleCommand.java`, `application/CreateOperationTypeCommand.java`, `application/PatchOperationTypeCommand.java`, `application/RuleManifestCompiler.java`, `application/port/out/LimitRuleRepository.java`
- `adapter/in/web/LimitRuleController.java`, `adapter/in/web/LimitRuleResponse.java`, `adapter/in/web/OperationTypeResponse.java`, новый `adapter/in/web/CounterpartyTypeResponse.java`
- `adapter/out/postgres/PostgresLimitRuleRepository.java`, `adapter/out/postgres/PostgresRuleManifestRepository.java`
- runtimeconfig: `domain/RuntimeCompiledRule.java`, `application/RuntimeManifestCompiler.java`, `adapter/out/postgres/PostgresRuntimeManifestRepository.java`
- limitassignment (Task 4): `domain/LimitAssignment.java`, `application/CreateLimitAssignmentCommand.java`, `application/PatchLimitAssignmentCommand.java`, `application/LimitAssignmentService.java`, `adapter/in/web/LimitAssignmentController.java`, `adapter/in/web/LimitAssignmentResponse.java`, `adapter/out/postgres/PostgresLimitAssignmentRepository.java`
- обновление затронутых тестов: `LimitRuleServiceTest`, `LimitRuleControllerTest`, `PostgresLimitRuleRepositoryIntegrationTest`, `RuleManifestCompilerTest`, `RuntimeManifestCompilerTest`, `PostgresRuleManifestRepositoryIntegrationTest`, `PostgresRuntimeManifestRepositoryIntegrationTest`, `LimitAssignment*Test`.

---

## Task 1: operation_type — counterparty_type + сверка 7 типов + справочные enum'ы

Дополняет справочник типов операций до формы §2.1 (direction+counterpartyType) и вводит read-only enum'ы `CounterpartyType`/`AggregationScope`. Модель правила не трогается — существующие правила продолжают работать.

**Files:**
- Create: `src/main/java/ru/copperside/paylimits/management/limitrule/domain/CounterpartyType.java`
- Create: `src/main/java/ru/copperside/paylimits/management/limitrule/domain/AggregationScope.java`
- Create: `src/main/java/ru/copperside/paylimits/management/limitrule/adapter/in/web/CounterpartyTypeResponse.java`
- Create: `src/main/resources/db/migration/V7__operation_type_counterparty.sql`
- Modify: `.../limitrule/domain/OperationType.java` (добавить `counterpartyType`)
- Modify: `.../limitrule/domain/RuleDictionaries.java` (+ `counterpartyTypes`, `aggregationScopes`)
- Modify: `.../limitrule/application/CreateOperationTypeCommand.java`, `.../PatchOperationTypeCommand.java`
- Modify: `.../limitrule/application/LimitRuleService.java` (construct OperationType с counterpartyType; validate)
- Modify: `.../limitrule/adapter/in/web/LimitRuleController.java` (запросы + `GET /counterparty-types`)
- Modify: `.../limitrule/adapter/in/web/OperationTypeResponse.java`
- Modify: `.../limitrule/adapter/out/postgres/PostgresLimitRuleRepository.java` (mapping/SQL/dictionaries)
- Test: `.../limitrule/adapter/out/postgres/PostgresLimitRuleRepositoryIntegrationTest.java`, `.../adapter/in/web/LimitRuleControllerTest.java`

**Interfaces:**
- Produces:
  - `enum CounterpartyType { CARD, PHONE, ACCOUNT }`
  - `enum AggregationScope { OWNER, TARGET }`
  - `OperationType(UUID id, String code, String name, String familyCode, OperationDirection direction, CounterpartyType counterpartyType, boolean enabled, int sortOrder, Instant createdAt, Instant updatedAt)`
  - `GET /internal/v1/limit-management/counterparty-types` → `ApiResponse<List<CounterpartyTypeResponse>>`

- [ ] **Step 1: Написать падающий integration-тест миграции/справочника**

В `PostgresLimitRuleRepositoryIntegrationTest` добавить:

```java
@Test
void listsSevenSpecOperationTypesWithCounterparty() {
    List<OperationType> types = repository.listOperationTypes();
    Map<String, OperationType> byCode = types.stream()
            .collect(Collectors.toMap(OperationType::code, t -> t));
    assertThat(byCode.keySet()).containsExactlyInAnyOrder(
            "ECOM", "AFT", "OCT", "SBP_C2B", "SBP_B2C", "SBP_B2B_IN", "SBP_B2B_OUT");
    assertThat(byCode.get("OCT").direction()).isEqualTo(OperationDirection.OUT);
    assertThat(byCode.get("OCT").counterpartyType()).isEqualTo(CounterpartyType.CARD);
    assertThat(byCode.get("AFT").direction()).isEqualTo(OperationDirection.IN);
    assertThat(byCode.get("SBP_B2C").counterpartyType()).isEqualTo(CounterpartyType.PHONE);
    assertThat(byCode.get("SBP_B2B_OUT").counterpartyType()).isEqualTo(CounterpartyType.ACCOUNT);
}
```

- [ ] **Step 2: Прогнать — убедиться, что не компилируется/падает**

Run: `./mvnw -q -Dspring.profiles.active=test -Dtest=PostgresLimitRuleRepositoryIntegrationTest test`
Expected: FAIL — нет метода `counterpartyType()` / нет типа `CounterpartyType`.

- [ ] **Step 3: Создать enum'ы**

`CounterpartyType.java`:
```java
package ru.copperside.paylimits.management.limitrule.domain;

public enum CounterpartyType {
    CARD,
    PHONE,
    ACCOUNT
}
```
`AggregationScope.java`:
```java
package ru.copperside.paylimits.management.limitrule.domain;

public enum AggregationScope {
    OWNER,
    TARGET
}
```

- [ ] **Step 4: Добавить поле в `OperationType`**

```java
public record OperationType(
        UUID id,
        String code,
        String name,
        String familyCode,
        OperationDirection direction,
        CounterpartyType counterpartyType,
        boolean enabled,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt
) {
}
```

- [ ] **Step 5: Миграция V7**

`V7__operation_type_counterparty.sql`:
```sql
-- Reconcile operation_types to the §2.1 catalog (7 types, direction IN|OUT, counterparty_type).
alter table limit_management.operation_types
    add column counterparty_type varchar(16);

-- Remove non-spec, ALL-direction rows (SBP_C2C, PSR) before tightening the CHECK.
delete from limit_management.operation_types where code in ('SBP_C2C', 'PSR');

-- Fix AFT direction (spec: AFT is IN), add missing B2B account types.
update limit_management.operation_types set direction = 'IN' where code = 'AFT';

insert into limit_management.operation_types
    (id, code, name, family_code, direction, counterparty_type, enabled, sort_order, created_at, updated_at)
values
    ('88888888-8888-8888-8888-888888888888', 'SBP_B2B_IN',  'SBP B2B incoming', 'SBP', 'IN',  'ACCOUNT', true, 80, now(), now()),
    ('99999999-9999-9999-9999-999999999999', 'SBP_B2B_OUT', 'SBP B2B outgoing', 'SBP', 'OUT', 'ACCOUNT', true, 90, now(), now())
on conflict (code) do nothing;

update limit_management.operation_types set counterparty_type = case code
    when 'ECOM' then 'CARD'
    when 'AFT' then 'CARD'
    when 'OCT' then 'CARD'
    when 'SBP_C2B' then 'PHONE'
    when 'SBP_B2C' then 'PHONE'
    when 'SBP_B2B_IN' then 'ACCOUNT'
    when 'SBP_B2B_OUT' then 'ACCOUNT'
    else counterparty_type
end;

alter table limit_management.operation_types
    alter column counterparty_type set not null,
    drop constraint operation_types_direction_chk,
    add constraint operation_types_direction_chk check (direction in ('IN', 'OUT')),
    add constraint operation_types_counterparty_chk check (counterparty_type in ('CARD', 'PHONE', 'ACCOUNT'));
```

Примечание: удаление ALL-строк требуется до сужения CHECK. FAMILY-код у B2B — `SBP` (существующая семья).

- [ ] **Step 6: Обновить repository — mapping/SQL/dictionaries**

В `PostgresLimitRuleRepository`:
- `mapOperationType`: добавить `CounterpartyType.valueOf(rs.getString("counterparty_type"))` в конструктор после `OperationDirection...`.
- Все три `select ... from operation_types`: добавить `counterparty_type` в список колонок.
- `saveOperationType` insert: добавить `counterparty_type` в колонки и `type.counterpartyType().name()` в значения.
- `updateOperationType`: добавить `counterparty_type = ?` и значение.
- `getRuleDictionaries`: добавить в конструктор `RuleDictionaries` списки `Arrays.asList(CounterpartyType.values())`, `Arrays.asList(AggregationScope.values())` (порядок — см. Step 7).

- [ ] **Step 7: Расширить `RuleDictionaries`**

Добавить в конец записи два поля (после `periods`):
```java
        List<RuleMetric> metrics,
        List<RulePeriod> periods,
        List<CounterpartyType> counterpartyTypes,
        List<AggregationScope> aggregationScopes
```
Обновить конструктор в `PostgresLimitRuleRepository.getRuleDictionaries` соответственно.

- [ ] **Step 8: Команды и контроллер operation-type + `/counterparty-types`**

`CreateOperationTypeCommand`: добавить поле `CounterpartyType counterpartyType`.
`PatchOperationTypeCommand`: добавить поле `CounterpartyType counterpartyType` (nullable для partial-update).
В `LimitRuleService.createOperationType`: пробросить `requireEnum(command.counterpartyType(), "counterpartyType")` в конструктор `OperationType`. В `patchOperationType`: `command.counterpartyType() == null ? existing.counterpartyType() : command.counterpartyType()`.
`OperationTypeResponse.from`: добавить `type.counterpartyType().name()`.
В `LimitRuleController`:
- `CreateOperationTypeRequest`/`PatchOperationTypeRequest` += `CounterpartyType counterpartyType` (`@NotNull` в create), пробросить в команды.
- Новый эндпоинт:
```java
@GetMapping("/counterparty-types")
public ApiResponse<List<CounterpartyTypeResponse>> listCounterpartyTypes() {
    return ApiResponse.success(CounterpartyTypeResponse.all(), clock);
}
```
`CounterpartyTypeResponse.java`:
```java
package ru.copperside.paylimits.management.limitrule.adapter.in.web;

import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;

import java.util.Arrays;
import java.util.List;

public record CounterpartyTypeResponse(String code, String name) {
    public static List<CounterpartyTypeResponse> all() {
        return Arrays.stream(CounterpartyType.values())
                .map(type -> new CounterpartyTypeResponse(type.name(), localize(type)))
                .toList();
    }

    private static String localize(CounterpartyType type) {
        return switch (type) {
            case CARD -> "Карта";
            case PHONE -> "Телефон";
            case ACCOUNT -> "Счёт";
        };
    }
}
```

- [ ] **Step 9: Поправить прочие call-site конструктора `OperationType`**

Найти и обновить все `new OperationType(...)` (Grep `new OperationType(`): тестовые фабрики и, если есть, миграционные фикстуры — добавить `CounterpartyType.CARD` (или корректный) в позицию после `direction`.

- [ ] **Step 10: Контроллер-тест на `/counterparty-types`**

В `LimitRuleControllerTest`:
```java
@Test
void returnsCounterpartyTypes() throws Exception {
    mockMvc.perform(get("/internal/v1/limit-management/counterparty-types"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].code",
                    containsInAnyOrder("CARD", "PHONE", "ACCOUNT")));
}
```

- [ ] **Step 11: Прогнать тесты**

Run: `./mvnw -q -Dspring.profiles.active=test -Dtest=PostgresLimitRuleRepositoryIntegrationTest,LimitRuleControllerTest test`
Expected: PASS.

- [ ] **Step 12: Полный прогон модуля (регрессия)**

Run: `./mvnw -q -Dspring.profiles.active=test test`
Expected: PASS (все существующие тесты зелёные — модель правила ещё не менялась).

- [ ] **Step 13: Commit**

```bash
git add src/main/java src/main/resources/db/migration/V7__operation_type_counterparty.sql src/test/java
git commit -m "feat: add counterparty type to operation catalog and reconcile spec operation types"
```

---

## Task 2: переформовка модели правила (§2.1) + миграция V8 + пропагация компиляции

Меняет `LimitRule` на форму §2.1 и распространяет изменение по репозиторию, контроллеру и обоим компиляторам манифеста, сохраняя зелёную сборку и существующие manifest/CRUD-тесты. Валидации 1–4 — в Task 3.

**Files:**
- Create: `.../limitrule/domain/Measure.java`
- Modify: `.../limitrule/domain/RuleMetric.java`, `.../RulePeriod.java`, `.../LimitTargetType.java`, `.../OperationDirection.java`, `.../LimitRule.java`, `.../CompiledRule.java`
- Delete: `.../limitrule/domain/OperationSelectorType.java`
- Modify: `.../limitrule/application/LimitRuleService.java`, `.../CreateLimitRuleCommand.java`, `.../PatchLimitRuleCommand.java`, `.../port/out/LimitRuleRepository.java`, `.../RuleManifestCompiler.java`
- Modify: `.../limitrule/adapter/in/web/LimitRuleController.java`, `.../LimitRuleResponse.java`
- Modify: `.../limitrule/adapter/out/postgres/PostgresLimitRuleRepository.java`
- Modify: `.../runtimeconfig/domain/RuntimeCompiledRule.java`, `.../runtimeconfig/application/RuntimeManifestCompiler.java`
- Create: `src/main/resources/db/migration/V8__limit_rule_reshape.sql`
- Test: `RuleManifestCompilerTest`, `RuntimeManifestCompilerTest`, `PostgresLimitRuleRepositoryIntegrationTest`, `LimitRuleControllerTest`, `PostgresRuleManifestRepositoryIntegrationTest`, `PostgresRuntimeManifestRepositoryIntegrationTest`

**Interfaces:**
- Produces:
  - `Measure(RuleMetric metric, RulePeriod period, AggregationScope aggregationScope, String currency, Integer intervalMinutes)`
  - `LimitRule(UUID id, String code, int version, String name, java.util.Set<String> operationTypes, OperationDirection direction, Measure measure, LimitTargetType limitTargetType, java.math.BigDecimal limitValue, String errorMessageTemplate, RuleSelector<AttributeSelectorType> attributeSelector, RuleStatus status, Instant createdAt, Instant updatedAt, Instant activatedAt, Instant disabledAt)` + метод `boolean active()`
  - `RuleMetric { AMOUNT, COUNT, INTERVAL }`, `RulePeriod { DAY, WEEK, MONTH, PER_OPERATION }`, `LimitTargetType { CARD, PHONE, ACCOUNT }`, `OperationDirection { IN, OUT }`
  - `CompiledRule.Matcher(List<String> operationTypes, OperationDirection direction, RuleSelector<AttributeSelectorType> attribute, LimitTargetType targetType)`; `CompiledRule.Measure` = alias на domain `Measure` поля (metric/period/aggregationScope/currency/intervalMinutes); `limitValue`, `errorMessageTemplate` — поля `CompiledRule`.

- [ ] **Step 1: Обновить enum'ы**

`RuleMetric`: `AMOUNT, COUNT, INTERVAL`. `RulePeriod`: `DAY, WEEK, MONTH, PER_OPERATION`. `LimitTargetType`: `CARD, PHONE, ACCOUNT` (убрать `ANY`). `OperationDirection`: `IN, OUT` (убрать `ALL`). Удалить файл `OperationSelectorType.java`.

- [ ] **Step 2: Создать `Measure`**

```java
package ru.copperside.paylimits.management.limitrule.domain;

public record Measure(
        RuleMetric metric,
        RulePeriod period,
        AggregationScope aggregationScope,
        String currency,
        Integer intervalMinutes
) {
}
```

- [ ] **Step 3: Переформовать `LimitRule`**

```java
package ru.copperside.paylimits.management.limitrule.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record LimitRule(
        UUID id,
        String code,
        int version,
        String name,
        Set<String> operationTypes,
        OperationDirection direction,
        Measure measure,
        LimitTargetType limitTargetType,
        BigDecimal limitValue,
        String errorMessageTemplate,
        RuleSelector<AttributeSelectorType> attributeSelector,
        RuleStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant activatedAt,
        Instant disabledAt
) {
    public LimitRule {
        operationTypes = operationTypes == null ? Set.of() : Set.copyOf(operationTypes);
    }

    public boolean active() {
        return status == RuleStatus.ACTIVE;
    }
}
```

- [ ] **Step 4: Миграция V8 (reshape limit_rules + junction)**

`V8__limit_rule_reshape.sql`:
```sql
-- Junction: rule → operation types (replaces the single operation_selector_*).
create table limit_management.limit_rule_operation_type (
    rule_id uuid not null references limit_management.limit_rules (id) on delete cascade,
    operation_type_code varchar(64) not null,
    constraint limit_rule_operation_type_pk primary key (rule_id, operation_type_code),
    constraint limit_rule_operation_type_code_fk
        foreign key (operation_type_code) references limit_management.operation_types (code)
);

-- Backfill from the old single selector (only TYPE-selectors carried a concrete code).
insert into limit_management.limit_rule_operation_type (rule_id, operation_type_code)
select id, operation_selector_value
from limit_management.limit_rules
where operation_selector_type = 'TYPE' and operation_selector_value is not null;

-- New rule columns.
alter table limit_management.limit_rules
    add column aggregation_scope varchar(16),
    add column interval_minutes integer,
    add column limit_value numeric(38, 2),
    add column error_message_template varchar(1024);

update limit_management.limit_rules
set error_message_template = 'Лимит превышен. Лимит %d, использовано %f, значение операции %s.'
where error_message_template is null;

-- Drop the retired single-selector columns and constraints.
alter table limit_management.limit_rules
    drop constraint if exists limit_rules_operation_selector_type_chk,
    drop constraint if exists limit_rules_operation_selector_value_chk,
    drop column operation_selector_type,
    drop column operation_selector_value;

-- Retarget CHECKs to the §2.1 model.
alter table limit_management.limit_rules
    drop constraint if exists limit_rules_direction_chk,
    drop constraint if exists limit_rules_target_type_chk,
    drop constraint if exists limit_rules_metric_chk,
    drop constraint if exists limit_rules_period_chk,
    drop constraint if exists limit_rules_currency_metric_chk,
    alter column target_type drop not null,
    add constraint limit_rules_direction_chk check (direction in ('IN', 'OUT')),
    add constraint limit_rules_target_type_chk check (target_type is null or target_type in ('CARD', 'PHONE', 'ACCOUNT')),
    add constraint limit_rules_metric_chk check (metric in ('AMOUNT', 'COUNT', 'INTERVAL')),
    add constraint limit_rules_period_chk check (period is null or period in ('DAY', 'WEEK', 'MONTH', 'PER_OPERATION')),
    add constraint limit_rules_error_template_not_blank check (length(trim(error_message_template)) > 0),
    -- Validation 1: PER_OPERATION ⇒ AMOUNT, no aggregation scope, no target.
    add constraint limit_rules_per_operation_chk check (
        period is distinct from 'PER_OPERATION'
        or (metric = 'AMOUNT' and aggregation_scope is null and target_type is null)
    ),
    -- Validation 2: INTERVAL ⇒ TARGET scope, interval_minutes > 0, no period, no limit_value.
    add constraint limit_rules_interval_chk check (
        metric <> 'INTERVAL'
        or (aggregation_scope = 'TARGET' and interval_minutes is not null and interval_minutes > 0
            and period is null and limit_value is null)
    ),
    -- AMOUNT/COUNT ⇒ limit_value present; INTERVAL ⇒ absent (covered above).
    add constraint limit_rules_limit_value_chk check (
        (metric in ('AMOUNT', 'COUNT') and limit_value is not null)
        or (metric = 'INTERVAL' and limit_value is null)
    ),
    -- TARGET scope ⇒ target type present; OWNER ⇒ absent.
    add constraint limit_rules_scope_target_chk check (
        aggregation_scope is distinct from 'TARGET' or target_type is not null
    );

drop index if exists limit_management.limit_rules_selector_idx;
create index limit_rules_status_code_idx
    on limit_management.limit_rules (status, code);
```

Примечание: колонка `attribute_selector_type/value` и её CHECK'и из V3 **остаются** (attributeSelector — расширение). `currency` CHECK ослаблен: валюта валидируется в сервисе (Task 3). Backfill limit_value для существующих ACTIVE-правил не делается — на greenfield их нет; если тестовые фикстуры создают правила напрямую в БД, обновить их под новые колонки.

- [ ] **Step 5: `hasActiveRulesForOperationTypeCode` — на junction**

В `PostgresLimitRuleRepository` заменить тело на:
```java
Boolean exists = jdbcTemplate.queryForObject("""
        select exists (
            select 1
            from limit_management.limit_rule_operation_type ot
            join limit_management.limit_rules r on r.id = ot.rule_id
            where ot.operation_type_code = ? and r.status = 'ACTIVE'
        )
        """, Boolean.class, operationTypeCode);
return Boolean.TRUE.equals(exists);
```

- [ ] **Step 6: `PostgresLimitRuleRepository` — SQL и mapping правила**

- `ruleSelect()`: убрать `operation_selector_*`; добавить `r.aggregation_scope, r.interval_minutes, r.limit_value, r.error_message_template`. Оставить `r.attribute_selector_type, r.attribute_selector_value, r.target_type, r.metric, r.period, r.currency, r.direction`.
- `saveRule`: insert без `operation_selector_*`, с новыми колонками; значения из `rule.measure()`/`rule.limitValue()`/`rule.errorMessageTemplate()`; `target_type` = `rule.limitTargetType() == null ? null : rule.limitTargetType().name()`. После insert правила — вставить строки в `limit_rule_operation_type` для каждого `rule.operationTypes()`.
- `updateRule`: update новых колонок; затем `delete from limit_rule_operation_type where rule_id = ?` и повторная вставка набора (правило DRAFT — набор мог измениться).
- `mapRule`: читать `operationTypes` отдельным запросом (`select operation_type_code from limit_rule_operation_type where rule_id = ?` → `Set`), собрать `Measure` из колонок, `limitValue` через `rs.getBigDecimal("limit_value")`, `errorMessageTemplate`, `limitTargetType` = `target_type` (nullable), сохранить чтение `attribute_selector_*`.

Полный пример `saveRule`:
```java
@Override
public LimitRule saveRule(LimitRule rule) {
    try {
        jdbcTemplate.update("""
                insert into limit_management.limit_rules
                    (id, code, version, name, direction,
                     attribute_selector_type, attribute_selector_value, target_type,
                     metric, period, aggregation_scope, currency, interval_minutes,
                     limit_value, error_message_template,
                     status, created_at, updated_at, activated_at, disabled_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rule.id(), rule.code(), rule.version(), rule.name(), rule.direction().name(),
                rule.attributeSelector().type().name(), rule.attributeSelector().value(),
                rule.limitTargetType() == null ? null : rule.limitTargetType().name(),
                rule.measure().metric().name(),
                rule.measure().period() == null ? null : rule.measure().period().name(),
                rule.measure().aggregationScope() == null ? null : rule.measure().aggregationScope().name(),
                rule.measure().currency(),
                rule.measure().intervalMinutes(),
                rule.limitValue(),
                rule.errorMessageTemplate(),
                rule.status().name(),
                Timestamp.from(rule.createdAt()), Timestamp.from(rule.updatedAt()),
                toTimestamp(rule.activatedAt()), toTimestamp(rule.disabledAt()));
        replaceOperationTypes(rule.id(), rule.operationTypes());
        return rule;
    } catch (DataIntegrityViolationException ex) {
        throw mapIntegrityViolation(ex);
    }
}

private void replaceOperationTypes(UUID ruleId, java.util.Set<String> codes) {
    jdbcTemplate.update("delete from limit_management.limit_rule_operation_type where rule_id = ?", ruleId);
    for (String code : codes) {
        jdbcTemplate.update(
                "insert into limit_management.limit_rule_operation_type (rule_id, operation_type_code) values (?, ?)",
                ruleId, code);
    }
}
```
`mapRule` (ключевые части):
```java
private LimitRule mapRule(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    Timestamp activatedAt = rs.getTimestamp("activated_at");
    Timestamp disabledAt = rs.getTimestamp("disabled_at");
    String period = rs.getString("period");
    String scope = rs.getString("aggregation_scope");
    String targetType = rs.getString("target_type");
    Integer intervalMinutes = (Integer) rs.getObject("interval_minutes");
    return new LimitRule(
            id,
            rs.getString("code"),
            rs.getInt("version"),
            rs.getString("name"),
            loadOperationTypes(id),
            OperationDirection.valueOf(rs.getString("direction")),
            new Measure(
                    RuleMetric.valueOf(rs.getString("metric")),
                    period == null ? null : RulePeriod.valueOf(period),
                    scope == null ? null : AggregationScope.valueOf(scope),
                    rs.getString("currency"),
                    intervalMinutes),
            targetType == null ? null : LimitTargetType.valueOf(targetType),
            rs.getBigDecimal("limit_value"),
            rs.getString("error_message_template"),
            new RuleSelector<>(
                    AttributeSelectorType.valueOf(rs.getString("attribute_selector_type")),
                    rs.getString("attribute_selector_value")),
            RuleStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            activatedAt == null ? null : activatedAt.toInstant(),
            disabledAt == null ? null : disabledAt.toInstant());
}

private java.util.Set<String> loadOperationTypes(UUID ruleId) {
    return new java.util.LinkedHashSet<>(jdbcTemplate.queryForList(
            "select operation_type_code from limit_management.limit_rule_operation_type where rule_id = ? order by operation_type_code",
            String.class, ruleId));
}
```
Обновить `mapIntegrityViolation`: добавить ветки для новых констрейнтов (`limit_rules_per_operation_chk`, `limit_rules_interval_chk`, `limit_rules_limit_value_chk`, `limit_rules_scope_target_chk`, `limit_rule_operation_type_code_fk`) → `INVALID_RULE_DEFINITION`.

- [ ] **Step 7: `LimitRuleRepository` порт**

Убрать `operationFamilyExists` из использования селектором (метод оставить, он используется словарями FAMILY для attributeSelector? нет — FAMILY был у operationSelector). Проверить: `operationFamilyExists` больше не нужен → удалить из порта и реализации, если нет других ссылок (Grep). `attributeValueExists` — оставить (attributeSelector жив).

- [ ] **Step 8: `CreateLimitRuleCommand` / `PatchLimitRuleCommand`**

```java
public record CreateLimitRuleCommand(
        String code,
        String name,
        java.util.Set<String> operationTypes,
        OperationDirection direction,
        Measure measure,
        LimitTargetType limitTargetType,
        java.math.BigDecimal limitValue,
        String errorMessageTemplate,
        RuleSelector<AttributeSelectorType> attributeSelector
) {
}
```
`PatchLimitRuleCommand` — те же поля, все nullable (partial update DRAFT).

- [ ] **Step 9: `LimitRuleService` — конструирование правила (без валидаций 1–4 пока)**

Переписать `createRule`/`patchRule`/`activateRule`/`disableRule`/`createNewVersion` под новый конструктор `LimitRule`. Заменить `validateOperationSelector`/`normalizeCurrency` на временный минимальный набор: `requireEnum(command.direction())`, `requireText(command.errorMessageTemplate())`, `attributeSelector` через существующий `validateAttributeSelector`, `operationTypes` — непустой Set существующих кодов (проверка через `repository.findOperationTypeByCode`). Полные валидации 1–4 — Task 3 (здесь только чтобы компилировалось и CRUD работал). `activateRule`/`disableRule`/`createNewVersion`: скопировать все новые поля existing-правила в новый record.

- [ ] **Step 10: Контроллер — `CreateRuleRequest`/`PatchRuleRequest`/`LimitRuleResponse`**

`CreateRuleRequest`:
```java
public record CreateRuleRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotEmpty java.util.Set<String> operationTypes,
        @NotNull OperationDirection direction,
        @Valid @NotNull MeasureRequest measure,
        LimitTargetType limitTargetType,
        java.math.BigDecimal limitValue,
        @NotBlank String errorMessageTemplate,
        @Valid AttributeSelectorRequest attributeSelector
) {
}

public record MeasureRequest(
        @NotNull RuleMetric metric,
        RulePeriod period,
        AggregationScope aggregationScope,
        String currency,
        Integer intervalMinutes
) {
    Measure toDomain() {
        return new Measure(metric, period, aggregationScope, currency, intervalMinutes);
    }
}
```
`PatchRuleRequest` — все поля nullable. `attributeSelector` в create: если `null` → сервис подставит `RuleSelector<>(NONE, null)`.
Убрать `OperationSelectorRequest`. В `createRule`/`patchRule` пробросить новые поля; `attributeSelector` = `request.attributeSelector() == null ? new RuleSelector<>(AttributeSelectorType.NONE, null) : request.attributeSelector().toDomain()`.
`LimitRuleResponse`:
```java
public record LimitRuleResponse(
        UUID id, String code, int version, String name,
        java.util.List<String> operationTypes, String direction,
        MeasureView measure, String limitTargetType,
        String limitValue, String errorMessageTemplate,
        Selector attributeSelector, String status, boolean enabled
) {
    public static LimitRuleResponse from(LimitRule rule) {
        Measure m = rule.measure();
        return new LimitRuleResponse(
                rule.id(), rule.code(), rule.version(), rule.name(),
                java.util.List.copyOf(rule.operationTypes()), rule.direction().name(),
                new MeasureView(m.metric().name(),
                        m.period() == null ? null : m.period().name(),
                        m.aggregationScope() == null ? null : m.aggregationScope().name(),
                        m.currency(), m.intervalMinutes()),
                rule.limitTargetType() == null ? null : rule.limitTargetType().name(),
                rule.limitValue() == null ? null : rule.limitValue().toPlainString(),
                rule.errorMessageTemplate(),
                new Selector(rule.attributeSelector().type().name(), rule.attributeSelector().value()),
                rule.status().name(), rule.active());
    }

    public record MeasureView(String metric, String period, String aggregationScope,
                              String currency, Integer intervalMinutes) {
    }

    public record Selector(String type, String value) {
    }
}
```

- [ ] **Step 11: `CompiledRule` + `RuleManifestCompiler` (limitrule)**

`CompiledRule`:
```java
public record CompiledRule(
        UUID ruleId, String code, int version,
        Matcher matcher, Measure measure,
        java.math.BigDecimal limitValue, String errorMessageTemplate
) {
    public record Matcher(
            java.util.List<String> operationTypes,
            OperationDirection direction,
            RuleSelector<AttributeSelectorType> attribute,
            LimitTargetType targetType
    ) {
        public Matcher {
            operationTypes = operationTypes == null ? java.util.List.of() : java.util.List.copyOf(operationTypes);
        }
    }
}
```
(`Measure` — доменный record из Step 2; убрать вложенный `CompiledRule.Measure`.)
В `RuleManifestCompiler`:
- `compileRule`: собрать `Matcher(new ArrayList<>(rule.operationTypes()).stream().sorted().toList(), rule.direction(), rule.attributeSelector(), rule.limitTargetType())`, `rule.measure()`, `rule.limitValue()`, `rule.errorMessageTemplate()`.
- Упростить `validateStructure`/`validateOperationSelector`: убрать логику ANY/FAMILY/TYPE; вместо неё проверять, что каждый код из `rule.operationTypes()` есть в `operationTypesByCode` и `enabled`, и что его `direction` совпадает с `rule.direction()` (ALL больше нет). Currency-проверку AMOUNT⇒RUB оставить.
- `detectOperationScopeOverlaps`/`operationScopesOverlap`: переписать через пересечение множеств `matcher.operationTypes()` (два правила с одинаковым non-operation ключом конфликтуют, если множества operationTypes пересекаются). `NonOperationKey` — без изменений по составу (direction, attribute, targetType, measure).
- `validateEnumMembership`: убрать проверки `operationSelectorTypes`; проверять `metrics/periods/targetTypes/directions/attributeSelectorTypes` (period/targetType могут быть null — учесть).
- `MatcherMeasureKey`: конструктор пересобирает `CompiledRule` — добавить `null` для новых полей limitValue/errorMessageTemplate, чтобы ключ считался по matcher+measure.

- [ ] **Step 12: `RuntimeCompiledRule` + `RuntimeManifestCompiler`**

`RuntimeCompiledRule`:
```java
public record RuntimeCompiledRule(
        UUID ruleId, String code, int version,
        Matcher matcher, Measure measure,
        java.math.BigDecimal limitValue, String errorMessageTemplate
) {
    public record Matcher(
            java.util.List<String> operationTypes,
            OperationDirection direction,
            RuleSelector<AttributeSelectorType> attribute,
            LimitTargetType targetType
    ) {
        public Matcher {
            operationTypes = operationTypes == null ? java.util.List.of() : java.util.List.copyOf(operationTypes);
        }
    }
}
```
(`Measure` — доменный.) В `RuntimeManifestCompiler.compileRule`: собрать из `rule.operationTypes()` (sorted list), `rule.measure()`, `rule.limitValue()`, `rule.errorMessageTemplate()`. Удалить хелпер `operationTypeCodes(...)` и параметр `operationTypes` там, где он больше не нужен для FAMILY-развёртки (теперь коды берутся прямо из правила). `schemaVersion`/canonical v2 — НЕ здесь (stage 4); сохранить текущую канонизацию, просто с новыми полями.

- [ ] **Step 13: Обновить manifest-тесты под новую compiled-модель**

`RuleManifestCompilerTest`, `RuntimeManifestCompilerTest`, `PostgresRuleManifestRepositoryIntegrationTest`, `PostgresRuntimeManifestRepositoryIntegrationTest`: заменить фикстуры правил на новый конструктор `LimitRule` (operationTypes-множество вместо selector; measure/limitValue/errorMessageTemplate). Сверить, что overlap-кейс теперь строится на пересечении множеств operationTypes.

- [ ] **Step 14: Прогнать манифест- и rule-тесты**

Run: `./mvnw -q -Dspring.profiles.active=test -Dtest=RuleManifestCompilerTest,RuntimeManifestCompilerTest,PostgresLimitRuleRepositoryIntegrationTest,LimitRuleControllerTest test`
Expected: PASS.

- [ ] **Step 15: Полный прогон (регрессия)**

Run: `./mvnw -q -Dspring.profiles.active=test test`
Expected: PASS.

- [ ] **Step 16: Commit**

```bash
git add -A
git commit -m "refactor: reshape limit rule model to spec §2.1 (operationTypes, measure, limitValue, error template)"
```

---

## Task 3: валидации 1–4 + errorMessageTemplate (MGT-U-01…05)

TDD-задача: полные правила валидации модели в `LimitRuleService`, поверх модели Task 2.

**Files:**
- Modify: `.../limitrule/application/LimitRuleService.java`
- Test: Create `.../limitrule/application/RuleValidationTest.java`

**Interfaces:**
- Consumes: `LimitRule`, `Measure`, `CounterpartyType`, `AggregationScope` (Task 1–2); `LimitRuleRepository.findOperationTypeByCode`.
- Produces: `LimitRuleService.createRule`/`patchRule` бросают `LimitRuleProblemException("VALIDATION_ERROR", …)` (маппится в 400) при нарушении валидаций 1–4 и шаблона.

- [ ] **Step 1: Падающие unit-тесты MGT-U-01…05**

`RuleValidationTest.java` (Mockito-мок репозитория, `Clock.fixed`):
```java
class RuleValidationTest {

    private LimitRuleRepository repository;
    private LimitRuleService service;

    @BeforeEach
    void setUp() {
        repository = mock(LimitRuleRepository.class);
        service = new LimitRuleService(repository, Clock.systemUTC());
        when(repository.nextVersion(anyString())).thenReturn(1);
        when(repository.findDraftByCode(anyString())).thenReturn(Optional.empty());
        stubOperationType("OCT", OperationDirection.OUT, CounterpartyType.CARD);
        stubOperationType("SBP_B2C", OperationDirection.OUT, CounterpartyType.PHONE);
    }

    private void stubOperationType(String code, OperationDirection dir, CounterpartyType cp) {
        when(repository.findOperationTypeByCode(code)).thenReturn(Optional.of(new OperationType(
                UUID.randomUUID(), code, code, "FAM", dir, cp, true, 0, Instant.EPOCH, Instant.EPOCH)));
    }

    // MGT-U-01: PER_OPERATION with metric=COUNT → 400
    @Test
    void rejectsPerOperationWithCountMetric() {
        var cmd = create(Set.of("OCT"), OperationDirection.OUT,
                new Measure(RuleMetric.COUNT, RulePeriod.PER_OPERATION, null, null, null),
                null, new BigDecimal("10"));
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class)
                .extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    // MGT-U-02: INTERVAL without intervalMinutes OR with aggregationScope=OWNER → 400
    @Test
    void rejectsIntervalWithoutMinutes() {
        var cmd = create(Set.of("SBP_B2C"), OperationDirection.OUT,
                new Measure(RuleMetric.INTERVAL, null, AggregationScope.TARGET, null, null),
                LimitTargetType.PHONE, null);
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class);
    }

    @Test
    void rejectsIntervalWithOwnerScope() {
        var cmd = create(Set.of("SBP_B2C"), OperationDirection.OUT,
                new Measure(RuleMetric.INTERVAL, null, AggregationScope.OWNER, null, 5),
                null, null);
        assertThatThrownBy(() -> service.createRule(cmd));
    }

    // MGT-U-03: operationTypes not matching direction → 400
    @Test
    void rejectsOperationTypesNotMatchingDirection() {
        var cmd = create(Set.of("OCT"), OperationDirection.IN,
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                null, new BigDecimal("100"));
        assertThatThrownBy(() -> service.createRule(cmd));
    }

    // MGT-U-04: TARGET rule mixing counterparties (OCT card + SBP_B2C phone) → 400
    @Test
    void rejectsTargetRuleMixingCounterparties() {
        var cmd = create(Set.of("OCT", "SBP_B2C"), OperationDirection.OUT,
                new Measure(RuleMetric.COUNT, RulePeriod.DAY, AggregationScope.TARGET, null, null),
                LimitTargetType.CARD, new BigDecimal("3"));
        assertThatThrownBy(() -> service.createRule(cmd));
    }

    // MGT-U-05: errorMessageTemplate with a bad placeholder (not %d/%f/%s) → 400
    @Test
    void rejectsInvalidTemplatePlaceholder() {
        var cmd = new CreateLimitRuleCommand("R", "name", Set.of("OCT"), OperationDirection.OUT,
                new Measure(RuleMetric.AMOUNT, RulePeriod.PER_OPERATION, null, "RUB", null),
                null, new BigDecimal("600000"),
                "Bad %x placeholder", new RuleSelector<>(AttributeSelectorType.NONE, null));
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class);
    }

    @Test
    void acceptsValidPerOperationAmountRule() {
        var cmd = new CreateLimitRuleCommand("R", "name", Set.of("OCT", "SBP_B2C"), OperationDirection.OUT,
                new Measure(RuleMetric.AMOUNT, RulePeriod.PER_OPERATION, null, "RUB", null),
                null, new BigDecimal("600000"),
                "Лимит %d использовано %f сумма %s", new RuleSelector<>(AttributeSelectorType.NONE, null));
        when(repository.saveRule(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.createRule(cmd).code()).isEqualTo("R");
    }

    private CreateLimitRuleCommand create(Set<String> ops, OperationDirection dir, Measure m,
                                          LimitTargetType target, BigDecimal value) {
        return new CreateLimitRuleCommand("R", "name", ops, dir, m, target, value,
                "Лимит %d использовано %f значение %s", new RuleSelector<>(AttributeSelectorType.NONE, null));
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что падают**

Run: `./mvnw -q -Dspring.profiles.active=test -Dtest=RuleValidationTest test`
Expected: FAIL (валидаций пока нет — часть тестов на «accepts» может проходить, «rejects» — падают).

- [ ] **Step 3: Реализовать валидации в `LimitRuleService`**

Добавить приватный `validateRuleDefinition(Set<String> operationTypes, OperationDirection direction, Measure measure, LimitTargetType target, BigDecimal limitValue, String template)` и вызвать его в `createRule`/`patchRule` перед сохранением:
```java
private void validateRuleDefinition(
        java.util.Set<String> operationTypeCodes,
        OperationDirection direction,
        Measure measure,
        LimitTargetType targetType,
        java.math.BigDecimal limitValue,
        String errorMessageTemplate) {
    if (operationTypeCodes == null || operationTypeCodes.isEmpty()) {
        throw problem("VALIDATION_ERROR", "operationTypes must contain at least one code");
    }
    requireEnum(direction, "direction");
    RuleMetric metric = requireEnum(measure == null ? null : measure.metric(), "measure.metric");
    RulePeriod period = measure.period();
    AggregationScope scope = measure.aggregationScope();

    // Resolve operation types and check direction (validation 3).
    java.util.List<OperationType> resolved = operationTypeCodes.stream()
            .map(code -> repository.findOperationTypeByCode(code)
                    .orElseThrow(() -> problem("RULE_OPERATION_TYPE_INVALID", "Operation type is not available: " + code)))
            .toList();
    for (OperationType type : resolved) {
        if (!type.enabled()) {
            throw problem("OPERATION_TYPE_DISABLED", "Operation type is disabled: " + type.code());
        }
        if (type.direction() != direction) {
            throw problem("VALIDATION_ERROR", "operationType " + type.code() + " does not match rule direction");
        }
    }

    // Validation 1: PER_OPERATION.
    if (period == RulePeriod.PER_OPERATION) {
        if (metric != RuleMetric.AMOUNT) {
            throw problem("VALIDATION_ERROR", "PER_OPERATION requires metric=AMOUNT");
        }
        if (scope != null) {
            throw problem("VALIDATION_ERROR", "PER_OPERATION must not define aggregationScope");
        }
        if (targetType != null) {
            throw problem("VALIDATION_ERROR", "PER_OPERATION must not define limitTargetType");
        }
    }

    // Validation 2: INTERVAL.
    if (metric == RuleMetric.INTERVAL) {
        if (scope != AggregationScope.TARGET) {
            throw problem("VALIDATION_ERROR", "INTERVAL requires aggregationScope=TARGET");
        }
        if (measure.intervalMinutes() == null || measure.intervalMinutes() <= 0) {
            throw problem("VALIDATION_ERROR", "INTERVAL requires intervalMinutes > 0");
        }
        if (period != null || limitValue != null) {
            throw problem("VALIDATION_ERROR", "INTERVAL must not define period or limitValue");
        }
    } else {
        // AMOUNT/COUNT need aggregationScope, and a limitValue.
        requireEnum(scope, "measure.aggregationScope");
        if (limitValue == null) {
            throw problem("VALIDATION_ERROR", "limitValue is required for AMOUNT/COUNT rules");
        }
        if (period == null) {
            throw problem("VALIDATION_ERROR", "period is required for AMOUNT/COUNT rules");
        }
    }

    // Validation 4: TARGET scope ⇒ single counterparty type == limitTargetType.
    if (scope == AggregationScope.TARGET) {
        if (targetType == null) {
            throw problem("VALIDATION_ERROR", "TARGET scope requires limitTargetType");
        }
        java.util.Set<CounterpartyType> counterparties = resolved.stream()
                .map(OperationType::counterpartyType)
                .collect(java.util.stream.Collectors.toSet());
        if (counterparties.size() != 1 || !counterparties.iterator().next().name().equals(targetType.name())) {
            throw problem("VALIDATION_ERROR",
                    "TARGET rule operationTypes must share a single counterparty equal to limitTargetType");
        }
    }

    // Currency: AMOUNT ⇒ RUB; non-AMOUNT ⇒ null.
    if (metric == RuleMetric.AMOUNT) {
        if (measure.currency() == null || !"RUB".equals(measure.currency().trim().toUpperCase())) {
            throw problem("VALIDATION_ERROR", "AMOUNT rules require currency=RUB");
        }
    } else if (measure.currency() != null) {
        throw problem("VALIDATION_ERROR", "currency is only allowed for AMOUNT rules");
    }

    // errorMessageTemplate: only %d/%f/%s placeholders allowed.
    validateErrorTemplate(errorMessageTemplate);
}

private static final java.util.regex.Pattern TEMPLATE_PLACEHOLDER =
        java.util.regex.Pattern.compile("%(.)");

private void validateErrorTemplate(String template) {
    String normalized = requireText(template, "errorMessageTemplate");
    java.util.regex.Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(normalized);
    while (matcher.find()) {
        String token = matcher.group(1);
        if (!java.util.Set.of("d", "f", "s", "%").contains(token)) {
            throw problem("VALIDATION_ERROR", "errorMessageTemplate contains unsupported placeholder %" + token);
        }
    }
}
```
Вызвать `validateRuleDefinition(...)` в `createRule` (с полями команды) и в `patchRule` (с эффективными полями после мержа с existing). Использовать `measure.currency()` нормализованный при сохранении.

- [ ] **Step 4: Прогнать — зелёные**

Run: `./mvnw -q -Dspring.profiles.active=test -Dtest=RuleValidationTest test`
Expected: PASS (все MGT-U-01…05 + accepts-кейсы).

- [ ] **Step 5: Обновить существующий `LimitRuleServiceTest`** под новый конструктор команд (operationTypes/measure/limitValue/errorMessageTemplate). Прогнать `-Dtest=LimitRuleServiceTest`.

- [ ] **Step 6: Полный прогон**

Run: `./mvnw -q -Dspring.profiles.active=test test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: enforce rule model validations 1-4 and error template placeholders (MGT-U-01..05)"
```

---

## Task 4: переезд limitValue на правило — удаление у назначения

Удаляет `limitValue` из назначения (значение теперь на правиле/версии; per-owner различия — версиями правила).

**Files:**
- Create: `src/main/resources/db/migration/V9__drop_assignment_limit_value.sql`
- Modify: `.../limitassignment/domain/LimitAssignment.java`, `.../application/CreateLimitAssignmentCommand.java`, `.../PatchLimitAssignmentCommand.java`, `.../application/LimitAssignmentService.java`, `.../adapter/in/web/LimitAssignmentController.java`, `.../adapter/in/web/LimitAssignmentResponse.java`, `.../adapter/out/postgres/PostgresLimitAssignmentRepository.java`
- Test: `LimitAssignmentServiceTest`, `LimitAssignmentControllerTest`, `PostgresLimitAssignmentRepositoryIntegrationTest`

**Interfaces:**
- Produces: `LimitAssignment(UUID id, UUID ruleId, AssignmentOwnerType ownerType, String ownerId, LimitMode limitMode, Instant validFrom, Instant validTo, boolean enabled, Instant createdAt, Instant updatedAt)` (без `limitValue`).

- [ ] **Step 1: Обновить integration-тест назначения** — убрать проверки limitValue; убедиться, что LIMITED-назначение создаётся без limitValue. Прогнать `-Dtest=PostgresLimitAssignmentRepositoryIntegrationTest` → FAIL (колонка/поле ещё есть).

- [ ] **Step 2: Миграция V9**

```sql
alter table limit_management.limit_assignments
    drop constraint if exists limit_assignments_limit_value_chk,
    drop column limit_value;
```

- [ ] **Step 3: Убрать `limitValue` из домена/команд/сервиса/репозитория/ответа**

- `LimitAssignment`: удалить поле `limitValue` (и из всех `new LimitAssignment(...)`).
- `CreateLimitAssignmentCommand`/`PatchLimitAssignmentCommand`: удалить `limitValue`.
- `LimitAssignmentService`: удалить `LIMIT_VALUE_PATTERN`, `validateLimitValue`, `resolvePatchLimitValue`; в create/patch/disable убрать аргумент `limitValue` из конструктора.
- `PostgresLimitAssignmentRepository`: убрать `limit_value` из select/insert/update и из `mapAssignment`.
- `LimitAssignmentResponse`: убрать `limitValue`.
- `LimitAssignmentController`: убрать `limitValue` из request-record и вызовов команд.

- [ ] **Step 4: Обновить тесты назначения** (`LimitAssignmentServiceTest`, `LimitAssignmentControllerTest`) — убрать limitValue.

- [ ] **Step 5: Прогнать**

Run: `./mvnw -q -Dspring.profiles.active=test -Dtest=LimitAssignmentServiceTest,LimitAssignmentControllerTest,PostgresLimitAssignmentRepositoryIntegrationTest test`
Expected: PASS.

- [ ] **Step 6: Полный прогон (DoD этапа 1)**

Run: `./mvnw -q -Dspring.profiles.active=test test`
Expected: PASS — все тесты зелёные, включая MGT-U-01…05.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move limit value from assignment to rule per spec §2.1/§4.3"
```

---

## DoD этапа 1

- MGT-U-01…05 зелёные (`RuleValidationTest`).
- CRUD правил принимает/отклоняет новые поля; `/counterparty-types` и расширенный `/rule-dictionaries` работают.
- Оба компилятора манифеста собирают правила в новой форме (существующие manifest-тесты зелёные); canonical v2/`schemaVersion` — этап 4.
- `limitValue` на правиле; назначение без `limitValue`.
- Полный прогон `./mvnw -Dspring.profiles.active=test test` зелёный.
- Swagger обновлён автоматически из аннотаций (проверить `/swagger-ui.html` при локальном запуске).

## Следующие этапы (отдельные планы)

- **Этап 3** — инвариант непересечения видов лимитов (три точки + advisory lock + единый `conflicts` DTO, 409/422). Кортеж вида лимита = (тип контроля=(metric,period), limitTargetType, direction, множество operationTypes); attributeSelector в кортеж не входит.
- **Этап 4** — манифест v2: `schemaVersion=2`, `businessTimezone`, `operationTypes` в снимке, attributeSelector как расширение, `If-None-Match`→304, повторная проверка инварианта→422, стабильность checksum (MGT-U-06/07). Веха **M1**.
