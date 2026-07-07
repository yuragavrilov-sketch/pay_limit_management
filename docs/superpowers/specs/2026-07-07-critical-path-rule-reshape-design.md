---
title: pay_limit_management — критический путь (этапы 1→3→4), дизайн переформовки модели правила
date: 2026-07-07
status: approved
scope: этапы 1 (модель правила), 3 (инвариант непересечения), 4 (манифест v2 → веха M1)
sources:
  - Постановка bcd9db1b-ec1a-4a59-aaaf-f2463fa9192f
  - Техспека 37462b5e-da37-4a88-9463-3e406ab3e785 (§2 доменная модель)
  - Спека сервиса 1f9a4b6c-9ff2-4a09-b510-061838e70d7d
  - План реализации 11d1f8de-9914-4339-a8c5-6a75df299052
---

## 1. Контекст и проблема

Базовый сервис уже построен (справочники, правила, группы, членства, назначения, rule/runtime-манифесты,
6 миграций, тесты на всех слоях). Однако **доменная модель правила в коде — ранний черновик и структурно
расходится со спекой §2.1**. По приоритету источников (Постановка > техспека > сервисная спека > код/черновики)
источник истины — спека; код подлежит переформовке.

### 1.1 Таблица расхождений код ↔ спека §2.1

| Аспект | Сейчас в коде | Спека §2.1 / §4.3 | Решение |
|---|---|---|---|
| Выбор операций | `operationSelector: RuleSelector<{ANY,FAMILY,TYPE}>` (один селектор) | `operationTypes: Set<code>` (≥1) | Заменить на множество |
| Целевой тип | `LimitTargetType{ANY,CARD,PHONE}` | `CARD\|PHONE\|ACCOUNT`, без ANY | += ACCOUNT, − ANY |
| Метрика | `AMOUNT\|COUNT` | `AMOUNT\|COUNT\|INTERVAL` | += INTERVAL |
| Период | `DAY\|WEEK\|MONTH` | += `PER_OPERATION` | += PER_OPERATION |
| aggregationScope | нет | `OWNER\|TARGET` (обяз.) | новый enum + поле |
| intervalMinutes | нет | для INTERVAL, >0 | новое поле |
| limitValue | **на назначении** (`LimitAssignment.limitValue`) | **на правиле/версии** | Переезд на правило |
| errorMessageTemplate | нет | обяз., плейсхолдеры `%d/%f/%s` | новое поле |
| attributeSelector | `RuleSelector<{PAYMENT_SYSTEM,ISSUER_COUNTRY,BIN,BANK,CARD_TYPE,CARD_LEVEL}>` | отсутствует | **Сохраняем как расширение** (решение заказчика) |
| operation_types.direction | CHECK `IN\|OUT\|ALL` | `IN\|OUT` | − ALL |
| operation_types.counterparty_type | нет | CARD/PHONE/ACCOUNT | новая колонка + сид |
| limit_rules.target_type | CHECK `= 'PHONE'` | CARD/PHONE/ACCOUNT | снять зажим |

### 1.2 Утверждённые решения (brainstorming 2026-07-07)

1. **Переформовать модель правила под §2.1** (не аддитивно). Ломающее изменение `/rules` допустимо — спека
   источник истины, а манифест v2 (веха M1) требует точную форму §4.3.
2. **`attributeSelector` сохраняется** как опциональное расширение сверх спеки (default `NONE`); протаскивается
   в манифест v2 как опциональное поле — согласуется с engine на вехе M1.
3. **`limitValue` переезжает на правило**; поле `limitValue` у назначения удаляется. Индивидуальные значения —
   отдельной версией правила (как в примере §2.1 «M42 → версия с limitValue=10»).
4. **`operationTypes`** хранится junction-таблицей `limit_rule_operation_type` (FK-целостность), не `text[]`.
5. **V7 — reshape существующей `limit_rules`** (копия greenfield, прод-данных нет).

## 2. Целевая доменная модель правила

```
LimitRule(
  id, code, version, name,
  operationTypes: Set<String>,        // ≥1 код operation_type; заменяет operationSelector
  direction: OperationDirection,      // IN | OUT
  measure: Measure,
  limitTargetType: LimitTargetType,   // CARD|PHONE|ACCOUNT; null для OWNER/PER_OPERATION
  limitValue: BigDecimal,             // null для INTERVAL; переезжает с назначения
  errorMessageTemplate: String,       // %d/%f/%s
  attributeSelector: RuleSelector<AttributeSelectorType>,  // расширение, default NONE
  status, version, createdAt, updatedAt, activatedAt, disabledAt
)

Measure(
  metric: RuleMetric,                 // AMOUNT | COUNT | INTERVAL
  period: RulePeriod,                 // DAY|WEEK|MONTH|PER_OPERATION; null для INTERVAL
  aggregationScope: AggregationScope, // OWNER | TARGET; null для PER_OPERATION
  currency: String,                   // для AMOUNT (только RUB на первой поставке); null иначе
  intervalMinutes: Integer            // для INTERVAL, >0; null иначе
)
```

Удаляются: `OperationSelectorType`, single-`operationSelector`, `LimitTargetType.ANY`.
Добавляются: `RuleMetric.INTERVAL`, `RulePeriod.PER_OPERATION`, `LimitTargetType.ACCOUNT`,
enum `AggregationScope`, enum `CounterpartyType`.

## 3. Этап 1 — модель правила и справочники

### 3.1 Валидации 1–4 (§2.1) — application-слой + CHECK где выразимо

1. `PER_OPERATION` ⇒ `metric=AMOUNT`; `aggregationScope` не задаётся; `limitTargetType` не задаётся.
2. `INTERVAL` ⇒ `aggregationScope=TARGET`, `intervalMinutes>0`; `period` и `limitValue` пусты.
3. Все `operationTypes` соответствуют `direction` правила.
4. `aggregationScope=TARGET` ⇒ counterparty-тип всех `operationTypes` одинаков и равен `limitTargetType`.

Плюс валидация `errorMessageTemplate`: допустимы только плейсхолдеры `%d`/`%f`/`%s` (MGT-U-05).

### 3.2 Справочники

- `operation_type` += `counterparty_type` (CARD/PHONE/ACCOUNT); сид 7 типов спеки с `direction`+`counterparty_type`:
  ECOM(IN,CARD), AFT(IN,CARD), OCT(OUT,CARD), SBP_C2B(IN,PHONE), SBP_B2C(OUT,PHONE),
  SBP_B2B_IN(IN,ACCOUNT), SBP_B2B_OUT(OUT,ACCOUNT). CHECK direction `IN|OUT`.
- Enum `CounterpartyType` + `GET /counterparty-types` (read-only, локализованные названия).
- `GET /rule-dictionaries` дополняется counterparty-типами и `aggregationScope`.

### 3.3 Миграция V7 (reshape)

- `limit_rules`: снять `operation_type_id` и CHECK `target_type='PHONE'`; добавить
  `aggregation_scope`, `interval_minutes`, `limit_value numeric(38,2)`, `error_message_template`,
  колонки attribute_selector; расширить CHECK metric (`+INTERVAL`), period (`+PER_OPERATION`).
- Новая таблица `limit_rule_operation_type(rule_id FK, operation_type_id FK, PK(rule_id, operation_type_id))`.
- `limit_assignments`: удалить `limit_value`.
- CHECK-констрейнты для валидаций 1–4 в выразимой части (напр. INTERVAL ⇒ period/limit_value NULL).

### 3.4 DoD этапа 1

MGT-U-01…05 зелёные; CRUD правил принимает/отклоняет новые поля; обновлены интеграционные тесты правил
и `RuntimeCompiledRule` приведён к форме §4.3 (см. §5); Swagger обновлён.

## 4. Этап 3 — инвариант непересечения видов лимитов (эскиз, детали в плане)

- «Вид лимита» = кортеж **(тип контроля=(metric,period), limitTargetType, direction, множество operationTypes)**;
  конфликт = совпадение типа контроля и целевого типа при пересечении множеств `operationTypes`.
  `attributeSelector` в кортеж **не входит** (по спеке) — зафиксировать как осознанное решение.
- Проверка в трёх точках (членство, назначение, активация правила) под **advisory lock**
  (`pg_advisory_xact_lock` по `merchant_id` для членств; по `rule`-ключу для назначений/активации).
- Единый DTO `conflicts` (§3.4 спеки), 409; повторная проверка при компиляции → 422.
- DoD: MGT-I-02…07, включая конкурентный MGT-I-06.

## 5. Этап 4 — манифест v2 → веха M1 (эскиз)

- `RuntimeCompiledRule` → форма §4.3: `operationTypes[]`, `direction`, `limitTargetType`,
  `measure{metric,period,aggregationScope}`, `limitValue`, `errorMessageTemplate`,
  **+ `attributeSelector` (опциональное расширение)**.
- Снимок += `operationTypes` (код→direction,counterpartyType), `businessTimezone`, `schemaVersion=2`.
- Валидация `effectiveFrom ≥ now`; повторная проверка инварианта → 422; канонизация v2 + SHA-256;
  `If-None-Match` → 304. Любое изменение алгоритма канонизации = инкремент `schemaVersion`.
- Стабильность checksum при переупорядоченном входе — MGT-U-06/07.
- DoD: MGT-I-08…13; **веха M1** — схема v2 (включая расширение attributeSelector) опубликована для engine.

На этапе 1 `RuntimeCompiledRule`/`RuleManifestCompiler` приводятся к новой доменной модели, чтобы канонизацию
v2 не переделывать дважды; финальные `schemaVersion`/`If-None-Match`/422 — на этапе 4.

## 6. Риски и координация

- **Веха M1 с engine**: форму v2, включая нестандартное поле `attributeSelector`, зафиксировать явно —
  иначе engine отвергнет манифест по неизвестной схеме (fail-closed).
- **Ломающие изменения** `/rules` и `/assignments` (уход `limitValue`) — согласовать с payadmin-bff.
- **CLAUDE.md «не ломать существующее»** относится к CRUD/lifecycle групп-членств-назначений и GiST периодов;
  переформовка полей правила и переезд `limitValue` — осознанное исключение, оговорённое здесь.
- Открытые вопросы Постановки №1 (ключ INTERVAL) и №4 (таймзона) не блокируют management этапы 1–4
  (гипотезы техспеки §6: карта/телефон получателя; Europe/Moscow).

## 7. Порядок

Этап 1 → этап 3 → этап 4 (M1). Каждый этап закрывается своими MGT-* тестами; переход к следующему —
только на зелёных тестах.
