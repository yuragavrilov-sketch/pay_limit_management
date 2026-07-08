# Review hardening — оставшиеся находки (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Укрупнённые задачи, TDD внутри, ревью-гейт между задачами.

**Goal:** Закрыть 6 отслеживаемых altitude/efficiency/maintainability-находок ревью + мелкие дубли, не меняя внешнее поведение (кроме явных надёжностных проверок) и не трогая checksum-контракт манифеста (кроме усиления его целостности).

**Tech Stack:** Java 21 records, Spring Boot 4/Jackson 3, PostgreSQL, JdbcTemplate + Flyway, JUnit 5 + Mockito, Testcontainers, Maven.

## Global Constraints
- Русский пользователю; код/комментарии английские. Без Lombok; constructor injection; JdbcTemplate; forward-only Flyway; application-слой Spring-free; деньги BigDecimal/String; время через Clock; синтетические id.
- Не менять wire-контракт манифеста (форма §4.3) и алгоритм канонизации (иначе = инкремент schemaVersion). Усиление целостности (ре-верификация) допустимо, но canonical bytes/checksum значение не должны измениться.
- Не переходить к следующей задаче с красными тестами. Коммиты `refactor:`/`fix:`/`perf:` после набора; без push без просьбы.
- Сборка (нет mvnw): `mvn -s settings.xml -Dspring.profiles.active=test clean test` (Docker).
- ВАЖНО исполнителю: после прогона COMMIT'ить самому и давать финальный статус, не возвращать управление в ожидании фонового прогона.

---

## Task 1: аудит-надёжность (D3/F3 + B1)

**Deliverable:**
- `audit/application/AuditRecorder`: добавить helper `<T> T writeAndRecord(String entityType, String action, Object before, java.util.function.Function<T,String> entityId, java.util.function.Supplier<T> write)` — выполняет `write`, затем `record(entityType, entityId.apply(result), action, before, result)`, возвращает result. Использовать его в ПРОСТЫХ мутациях (createRule/patch/disable/newVersion, createOperationType/patchOperationType, createType/updateType/createGroup/updateGroup, createAssignment(after existing checks)/patch/disable) внутри их `transactionRunner.run(...)`, чтобы связка «write+record» была единой, а не копипастой. Для lock-несущих методов (assignMembership/createAssignment/activateRule) — оставить явный run() с lock/инвариантом, но запись аудита провести через тот же helper/record (не ломать порядок).
- **Completeness-тест (структурная защита от «забыл аудит»):** интеграционный тест, который для КАЖДОГО мутирующего эндпоинта (`POST/PATCH /rules`, `/rules/{id}/activate|disable|new-version`, `/operation-types`, `/merchant-group-types`, `/merchant-groups`, `/merchant-group-memberships` (+/close), `/assignments` (+/disable), `POST /runtime-manifests` (+/rollback), `POST /rule-manifests`) вызывает его валидным запросом с `X-Operator-Id` и проверяет, что появилась ≥1 запись в `audit_event` с корректным entity_type/action. Tier-move членства должен дать 2 записи (ASSIGN+CLOSE — уже реализовано). Это ловит будущие пропуски аудита.
- **B1 empty-operationTypes guard:** в `RuntimeManifestCompiler` (compileRule/buildManifest) добавить проверку: активное правило с пустым `operationTypes` → бросить `RuntimeManifestProblemException`/диагностику (как делает `RuleManifestCompiler.validateStructure`), а не эмитить RuleV2 с `operationTypes=[]` в манифест engine. Тест: правило с пустым набором (сконструированное в обход валидаций) → компиляция отвергает, не эмитит пустой matcher.

**DoD:** helper используется; completeness-тест зелёный и покрывает все мутирующие эндпоинты; empty-operationTypes отвергается; full suite зелёный. Commit `refactor: shared audited-write helper, audit-completeness test, guard empty operationTypes in runtime compiler`.

---

## Task 2: целостность checksum манифеста (F1 + E4)

**Deliverable:**
- **F1 ре-верификация при записи (fail-closed на дрейф):** в `PostgresRuntimeManifestRepository.saveCompiledManifest` ПОСЛЕ вставки прочитать заголовок+payload обратно (или использовать уже имеющийся read-back), пересчитать checksum по спроецированному `ManifestDocumentV2` из прочитанного payload_json и сравнить со stored checksum; расхождение → бросить (транзакция откатывается, манифест не публикуется). Это гарантирует, что то, что отдастся engine (проекция из payload_json), хешируется в сохранённый checksum — ловит любой дрейф round-trip jsonb на этапе записи, один раз, а не на каждом GET. (Не добавлять пересчёт на каждый GET — дорого.)
- **E4 убрать двойную канонизацию:** сейчас `validateManifest` пересчитывает checksum по payload, дублируя вычисление из `RuntimeManifestCompiler.buildManifest`. Согласовать: оставить ОДИН авторитетный расчёт. Если F1-ре-верификация при записи покрывает проверку целостности, то отдельный `validateManifest`-пересчёт (сверка factory-checksum) можно заменить/убрать так, чтобы на один compile приходился ровно один полный канонический пересчёт checksum + один ре-верификационный по прочитанному payload (это осознанная проверка round-trip, не избыточность). Задокументировать, какой расчёт зачем.
- Тесты: (1) нормальный compile проходит и checksum совпадает end-to-end (stored == recompute(document(readback)) — MGT-U/golden-vector остаются зелёными, значение checksum НЕ меняется); (2) искусственный дрейф (замокать/подменить payload при read-back так, чтобы document отличался) → запись падает fail-closed. Golden-vector/checksum-стабильность тесты обязаны остаться зелёными и с тем же значением.

**DoD:** запись ре-верифицирует document-checksum (fail-closed); ровно один «лишний» пересчёт устранён; checksum-значение неизменно; full suite зелёный. Commit `fix: verify manifest document checksum on write and remove redundant recompute`.

---

## Task 3: N+1 инварианта одним запросом (E2) + wither'ы записей (D4)

**Deliverable:**
- **E2:** `LimitKindInvariantChecker.collectGroupConflicts` — заменить цикл `for member in membersOfGroup: kindsReceivedByMerchantExcludingGroup(member, group, at)` на ОДИН запрос, отдающий для всех членов группы их виды из ДРУГИХ групп (member→kind→otherGroup) за один round-trip. Ввести порт-метод `List<MemberOtherGroupKind> kindsReceivedByMembersOfGroup(UUID groupId, Instant at)` (member id + LimitKind + otherGroupId, исключая сам groupId, с тем же окном валидности назначений и членств из фикса #2) + Postgres-реализация (JOIN memberships того же groupId → merchant → его другие группы → их enabled+active назначения активных правил). Собирать конфликты в памяти. Снижает время удержания advisory lock. Существующие MGT-I-04/05/06 остаются зелёными.
- **D4:** добавить wither-методы: `LimitRule.withStatus(status, activatedAt, disabledAt)` (или узкие `activated(now)`, `disabled(now)`, `asDraftNewVersion(id, version, now)`) и `LimitAssignment.withMode/withPeriod/disabled` — и заменить ими позиционную пересборку 16-арг/10-арг record'ов в `LimitRuleService.activateRule/disableRule/createNewVersion` и `LimitAssignmentService.patchAssignment/disableAssignment`. Устраняет риск тихой транспозиции одинаково-типизированных полей. Поведение идентично (тесты не меняются по смыслу).

**DoD:** collectGroupConflicts делает один запрос вместо N; wither'ы применены; поведение инварианта/CRUD не изменилось; full suite зелёный. Commit `perf: single-query group conflict scan; refactor: wither methods for rule/assignment lifecycle`.

---

## Task 4: дедуп и косметика

**Deliverable:**
- Вынести общий `loadOperationTypesForRules(List<UUID>)` (сейчас копия в `PostgresLimitRuleRepository`, `PostgresRuntimeManifestRepository`, `PostgresEffectiveLimitsRepository`) в общий helper (напр. `common/persistence` или статический util), переиспользовать. Аналогично `mapRule`/`withOperationTypes` где идентично.
- Вынести общие `requireCommand/requireUuid/requireInstant/requireEnum/requireText` (копия в 3 сервисах) в общий Spring-free helper, параметризованный фабрикой ProblemException (или базовым классом).
- Общая логика `compileRule` v1/v2 (RuleManifestCompiler vs RuntimeManifestCompiler) — вынести маппинг matcher/measure в общий util (разные целевые record'ы — оставить, но общий предвычисление operationTypes-sorted). Осторожно: не изменить canonical output (checksum-стабильность обязательна).
- GLOBAL null-owner_id sentinel `coalesce(owner_id,'')` — задокументировать единым комментарием в трёх местах (SQL overlap, V11 DDL — уже применена, только комментарий в коде, миграцию не трогать; ManifestDocumentV2Mapper.toOwner) или ввести именованную константу/util для представления GLOBAL-owner в overlap-запросе.
- PHONE-сид-шаблоны (V15): миграция иммутабельна (уже применена) — НЕ править V15. Вместо этого: если нужно, отдельной data-миграцией V16 скорректировать `error_message_template` у DRAFT PODFT-PHONE-* правил на формулировку «на телефон» (правила DRAFT, не активны — безопасно). Либо оставить оператору (тогда просто зафиксировать в отчёте). Реши по месту; если правишь — V16, forward-only, только DRAFT-строки.

**DoD:** дубли устранены без изменения поведения и checksum; full suite зелёный. Commit `refactor: extract shared operation-type loading, request validation, and owner-sentinel helpers`.

---

## DoD (весь план)
- Аудит атомарен и структурно проверен на полноту; пустой operationTypes отвергается компилятором.
- Checksum манифеста ре-верифицируется при записи (fail-closed), без избыточного двойного расчёта; значение checksum и wire-контракт не изменены.
- Инвариант-скан группы — один запрос; lifecycle-переходы правил/назначений через wither'ы (без позиционного риска).
- Дубли вынесены; косметика закрыта.
- Полный прогон зелёный; M1/M2 контракты не сломаны.
