# Техдолг management (этапы 1–4) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Закрыть накопленные non-blocking follow-up'ы этапов 1–4 (маппинг ошибок, хрупкость сравнения enum'ов, DB-инвариант, namespacing advisory lock, N+1, косметика), не меняя внешнее поведение сверх исправлений и не трогая уже применённые миграции.

**Architecture:** Гексагональная; правки точечные в существующих классах + одна новая миграция (V13). Поведение инварианта/манифеста/CRUD сохраняется, кроме явно указанных исправлений кодов ошибок.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL, JdbcTemplate + Flyway, JUnit 5 + Mockito, Testcontainers, Maven.

## Global Constraints
- Русский пользователю; код/имена/комментарии английские. Без Lombok; constructor injection; JdbcTemplate; forward-only Flyway; Maven.
- Деньги строками/BigDecimal; время через `Clock`; синтетические идентификаторы в тестах.
- Не менять уже применённые миграции (V1–V12) — только новые.
- Не менять семантику инварианта непересечения и манифеста v2 (checksum-контракт M1) — правки только не-контрактные (namespacing lock не меняет наблюдаемое поведение; DB-CHECK — defense-in-depth поверх уже действующей app-валидации).
- Advisory-lock namespacing НЕ должен изменить сериализацию (merchant vs rule по-прежнему сериализуются между собой корректно) — только устранить кросс-доменные ложные коллизии.
- Не переходить к следующей задаче с красными тестами. Коммиты `fix:`/`refactor:` после набора; без push без просьбы.
- Сборка (нет mvnw): `mvn -s settings.xml -Dspring.profiles.active=test clean test` (Docker). Focused: `-Dtest=Class1,Class2`.

## Не входит (обосновано)
- Самозащита backfill V8 от FK — миграция иммутабельна (уже применена), ретро-правка невозможна; на greenfield путь мёртв.
- `at=now()` vs `validFrom` в интерактивных проверках инварианта — продуктовое решение о семантике (в какой момент проверять конфликт для будущих изменений); выносится отдельно, поведение не меняем.

---

## Task 1: корректность ошибок, enum-маппинг, DB-инвариант, advisory-lock namespacing

**Files:**
- Modify: `common/web/GlobalExceptionHandler.java` (OPERATION_TYPE_DISABLED → 400)
- Modify: `limitrule/application/LimitRuleService.java` (явный CounterpartyType→LimitTargetType маппинг в валидации 4)
- Create: `src/main/resources/db/migration/V13__rule_owner_scope_target_null_check.sql`
- Modify: `common/invariant/adapter/PostgresLimitKindInvariantRepository.java` (2-арг advisory lock с namespace)
- Test: `LimitRuleControllerTest`/`PostgresLimitRuleRepositoryIntegrationTest`, `RuleValidationTest`, `PostgresLimitKindInvariantRepositoryIntegrationTest`, инвариант-интеграция (регресс)

**Interfaces:** без изменений сигнатур публичного API; меняются только HTTP-код для `OPERATION_TYPE_DISABLED` (409→400) и внутренняя реализация.

- [ ] **Step 1: OPERATION_TYPE_DISABLED → 400.** В `GlobalExceptionHandler.handleLimitRuleProblem` добавить `OPERATION_TYPE_DISABLED` в ветку `BAD_REQUEST` (сейчас уходит в `default -> CONFLICT`). `OPERATION_TYPE_IN_USE` оставить 409 (это настоящий конфликт при отключении используемого типа). Тест: создание правила со ссылкой на disabled operation type → HTTP 400 (не 409). (Найти/добавить тест в `LimitRuleControllerTest` или integration.)

- [ ] **Step 2: явный enum-маппинг в валидации 4.** В `LimitRuleService.validateRuleDefinition` заменить сравнение `counterparties.iterator().next().name().equals(targetType.name())` на явную функцию `counterpartyMatchesTarget(CounterpartyType, LimitTargetType)` (switch, полное покрытие: CARD↔CARD, PHONE↔PHONE, ACCOUNT↔ACCOUNT). Так расхождение enum'ов даст ошибку компиляции, а не тихий рантайм-мисматч. Поведение не меняется (MGT-U-04 остаётся зелёным). Тест: существующий MGT-U-04 + accept-кейсы.

- [ ] **Step 3: DB-CHECK converse (V13).**
```sql
-- Defense-in-depth поверх app-валидации: target_type задан ТОЛЬКО при aggregation_scope = TARGET.
alter table limit_management.limit_rules
    add constraint limit_rules_owner_scope_no_target_chk
    check (aggregation_scope is not distinct from 'TARGET' or target_type is null);
```
(На greenfield нарушающих строк нет — app-валидация этапа 1 их уже не допускает. `is not distinct from` корректно обрабатывает NULL scope: для PER_OPERATION scope NULL ≠ 'TARGET' → требует target_type NULL, что верно.) Тест: попытка вставки OWNER-правила с target_type через репозиторий → отказ (или проверить, что app-валидация + CHECK согласованы).

- [ ] **Step 4: advisory lock namespace.** В `PostgresLimitKindInvariantRepository`: `lockMerchant` → `select pg_advisory_xact_lock(?, hashtext(?))` с namespace-константой (напр. `1`) первым аргументом; `lockRule` → тем же с namespace `2`. Ввести приватные `static final int MERCHANT_LOCK_NS = 1`, `RULE_LOCK_NS = 2`. Устраняет кросс-доменные коллизии merchant/rule в общем 32-битном keyspace. Наблюдаемое поведение (сериализация одинаковых ключей) не меняется. Тест: существующие invariant/concurrency-тесты (MGT-I-06) остаются зелёными; advisory-методы не бросают.

- [ ] **Step 5: full suite → GREEN. Commit** `fix: correct operation-type-disabled status, harden enum match, add owner-scope DB check and advisory-lock namespaces`.

---

## Task 2: N+1 в mapRule + косметика (sort, rename, комментарии)

**Files:**
- Modify: `limitrule/adapter/out/postgres/PostgresLimitRuleRepository.java` (batch operationTypes для listRules; устранить per-rule запрос)
- Modify: `runtimeconfig/adapter/out/postgres/PostgresRuntimeManifestRepository.java` (убрать/выровнять редундантный `order by owner_id`)
- Modify: `runtimeconfig/application/RuntimeManifestCanonicalJson.java` (rename `bytes`→`payloadBytes`)
- Modify: `common/invariant/LimitKindInvariantChecker.java` (комментарий про cross-checkpoint gap) и `PostgresLimitKindInvariantRepository.java` (комментарий про INNER vs LEFT join, при желании выровнять)
- Test: существующие (регресс) + при необходимости точечный на batch-загрузку

**Interfaces:** без изменений публичного поведения.

- [ ] **Step 1: устранить N+1 в `mapRule`.** Сейчас `mapRule` вызывает `loadOperationTypes(ruleId)` отдельным запросом на каждое правило → N+1 на `listRules`/компиляции. Вариант: для `listRules()` загрузить все junction-строки одним запросом (`select rule_id, operation_type_code from limit_rule_operation_type` для набора id или всех) и раздать по правилам; `findRule`/`findDraftByCode`/`findActiveByCode` (одиночные) могут остаться как есть. Реализовать так, чтобы порядок/содержимое `Set<String>` operationTypes не изменились (детерминизм манифеста!). Тест: `listRules` с несколькими правилами, каждое с ≥2 op-types, возвращает те же множества, что и `findRule`; существующие manifest-checksum тесты зелёные.

- [ ] **Step 2: редундантный sort.** В `PostgresRuntimeManifestRepository.listEnabledAssignmentsForCompilation` (или где `order by ... owner_id`) SQL-сортировка избыточна — источник истины детерминизма — Java-компаратор `nullsFirst` в компиляторе. Убрать `order by owner_id`-часть (или выровнять на `owner_id asc nulls first` для читаемости). Не менять итоговый порядок в payload (его задаёт Java-sort). Тест: manifest-checksum/GLOBAL-тесты зелёные.

- [ ] **Step 3: rename** `RuntimeManifestCanonicalJson.bytes(RuntimeManifestPayload)` → `payloadBytes(...)` для симметрии с `documentBytes(...)`; обновить все call-sites. Чисто рефакторинг имени.

- [ ] **Step 4: комментарии.** В `LimitKindInvariantChecker` добавить краткий комментарий, что membership-checkpoint (lock по merchant) и assignment/activation-checkpoint (lock по rule) не сериализуются между собой по разным ключам, и что backstop — повторная проверка при компиляции (422). В `PostgresLimitKindInvariantRepository` — однострочный комментарий про осознанную асимметрию INNER (`kindsDeliveredByGroup`) vs LEFT (`kindOfRule`) join.

- [ ] **Step 5: full suite → GREEN. Commit** `refactor: batch rule operation-type loading, drop redundant sort, rename payloadBytes, add invariant comments`.

---

## DoD
- OPERATION_TYPE_DISABLED → 400; валидация 4 на явном enum-маппинге; V13 DB-CHECK применяется; advisory lock в раздельных namespace; `listRules` без N+1; косметика применена.
- Полный прогон `mvn -s settings.xml -Dspring.profiles.active=test clean test` зелёный; поведение инварианта и checksum-контракт M1 не изменились (manifest-checksum/golden-vector тесты зелёные).
- Открытый вопрос семантики `at` (будущие членства) вынесен пользователю отдельно, поведение не тронуто.
