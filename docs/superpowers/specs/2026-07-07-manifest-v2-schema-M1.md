---
title: pay_limit_management — runtime-манифест v2, схема §4.3 для engine (веха M1)
date: 2026-07-07
status: as-built (сверено с кодом на feat/manifest-v2)
scope: этап 4 (манифест v2), задача 4 — эмишн приведён к форме техспеки §4.3 (v2 wire-контракт)
sources:
  - Код: ManifestDocumentV2 (+ RuleV2/MeasureV2/AttributeSelectorV2/AssignmentV2/OwnerV2/MembershipV2/
    OperationTypeV2), ManifestDocumentV2Mapper, RuntimeManifestCanonicalJson, RuntimeManifestCompiler,
    RuntimeManifestResponse, RuntimeManifestController, RuntimeManifestPayload (внутренняя модель),
    GlobalExceptionHandler (все — `src/main/java/.../runtimeconfig/**`, `.../common/web/**`)
  - Спека сервиса 1f9a4b6c-9ff2-4a09-b510-061838e70d7d, §4.1–4.5
  - Техспека 37462b5e-da37-4a88-9463-3e406ab3e785, §2 (доменная модель, attributeSelector), §4.3 (форма документа)
---

Этот документ описывает форму runtime-манифеста v2, как его эмитит `pay_limit_management` на момент
вехи M1 (ветка `feat/manifest-v2`). Эмишн приведён к форме техспеки §4.3 **один в один** (задача 4);
единственное расширение сверх §4.3 — поле `rules[].attributeSelector` (см. §3.1). Всё остальное
совпадает с §4.3 по именам и структуре полей.

## 0. Две разные JSON-формы — не путать

Существуют **два разных** сериализованных представления манифеста:

1. **Wire-форма (HTTP-ответ)** — то, что реально возвращают эндпоинты `GET/POST
   /internal/v1/limit-management/runtime-manifests/**`: `RuntimeManifestResponse`, обёрнутый в
   `ApiResponse{data, meta?, error, timestamp}`. Внутри `data` — лёгкая обёртка с lifecycle-метаданными
   (`id`, `checksum`, `status`, `createdAt`, `ruleCount`/`assignmentCount`/`membershipCount`) и
   вложенным полем **`document`** — это и есть §4.3-документ (`ManifestDocumentV2`). Обёртка
   сериализуется дефолтным Jackson Spring Boot (порядок ключей — порядок объявления, `meta` опущен при
   null, `error` = null при успехе).
2. **Канонический JSON документа (для checksum)** — тот же `ManifestDocumentV2`, но сериализованный
   отдельным каноническим `ObjectMapper` (`RuntimeManifestCanonicalJson`) с
   `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY` + `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`
   (ключи отсортированы по алфавиту на каждом уровне вложенности), ISO-датами и **явными null**. Над
   этими байтами считается `checksum = "sha256:" + hex(SHA-256(...))`.

**Ключевой инвариант для engine:** `data.document` (по HTTP) и канонический документ (для checksum) —
это **один и тот же набор полей** `ManifestDocumentV2`, отличается только порядок ключей и
сериализатор. Чтобы проверить `checksum`, engine берёт `data.document`, канонизирует его по правилам §5
(сортировка ключей по алфавиту на всех уровнях, ISO-даты, явные null, **без** обёрточных полей `id`/
`checksum`/`status`/counts) и сверяет SHA-256 с `data.checksum`.

> Замечание по хранению: во внутренней БД management колонка `payload_json` хранит **внутреннюю**
> модель `RuntimeManifestPayload` (с денормализациями вроде `assignments[].ruleCode`), а не
> `ManifestDocumentV2`. Это деталь реализации management (нужна для точного read-back и rollback);
> engine её не видит и не должен на неё закладываться — контракт engine это ровно `data.document` из
> HTTP-ответа. Документ детерминированно проецируется из внутренней модели на границе сериализации, и
> `checksum` считается именно над документом.

## 1. Верхний уровень HTTP-обёртки (`RuntimeManifestResponse` внутри `data`)

```
{
  "id": "<uuid>",                     // id манифеста в БД management; НЕ входит в checksum
  "checksum": "sha256:<64 hex>",      // SHA-256 канонического document; лежит РЯДОМ с document, не внутри
  "status": "VALID",                  // lifecycle-метаданные management, не часть §4.3-документа
  "createdAt": "2026-07-06T12:00:00Z",
  "ruleCount": 1,
  "assignmentCount": 2,
  "membershipCount": 1,
  "document": { ... §4.3, см. ниже ... }
}
```

`id`, `checksum`, `status`, `createdAt`, `ruleCount/assignmentCount/membershipCount` — это
management-side метаданные жизненного цикла, **не входят** в хешируемый `document`. Счётчики
избыточны (равны длине соответствующих массивов внутри `document`) и даны для быстрой проверки без
парсинга.

## 2. `document` — §4.3-форма (то, над чем считается checksum)

```
{
  "schemaVersion": 2,
  "manifestVersion": 42,
  "effectiveFrom": "2026-07-10T00:00:00Z",
  "businessTimezone": "Europe/Moscow",
  "operationTypes": [ ... §2.1 ... ],
  "rules": [ ... §3 ... ],
  "assignments": [ ... §4 ... ],
  "memberships": [ ... §5 ... ]
}
```

| Поле | Тип | Заметки |
|---|---|---|
| `schemaVersion` | int | всегда `2` для свежескомпилированных манифестов. **Rollback — исключение**: см. §8 |
| `manifestVersion` | int | версия манифеста (внутреннее поле `version`), монотонно растёт |
| `effectiveFrom` | ISO-8601 Instant (UTC, `Z`) | момент вступления в силу; на компиляции проверяется лид-тайм, §7 |
| `businessTimezone` | string (IANA zone id) | зафиксирован на момент компиляции; смена конфига действует со следующей компиляции |
| `operationTypes` | array | справочник типов операций, §2.1 |
| `rules` | array | §3 |
| `assignments` | array | §4, все три уровня владельца, включая GLOBAL |
| `memberships` | array | §5 |

Пустая конфигурация (нет активных правил) — валидный документ с `rules: [], assignments: [],
memberships: []` (MGT-I-11).

### 2.1. `operationTypes[]`

```json
{"code": "OCT", "direction": "OUT", "counterpartyType": "CARD"}
```

`direction` ∈ `IN | OUT`. `counterpartyType` ∈ `CARD | PHONE | ACCOUNT`. Список отсортирован по `code`
на компиляции.

## 3. `rules[]` — плоская §4.3-форма (+ extension `attributeSelector`)

```json
{
  "ruleId": "0d9f1c2e-0000-4000-8000-000000000001",
  "code": "PAYOUT-CARD-COUNT-DAY",
  "version": 2,
  "measure": {
    "metric": "COUNT",
    "period": "DAY",
    "aggregationScope": "TARGET",
    "currency": null,
    "intervalMinutes": null
  },
  "limitValue": "3",
  "operationTypes": ["OCT"],
  "direction": "OUT",
  "limitTargetType": "CARD",
  "errorMessageTemplate": "Limit exceeded: %d of %f (%s)",
  "attributeSelector": {"type": "NONE", "value": null}
}
```

**Форма плоская — обёртки `matcher{}` НЕТ.** `operationTypes`, `direction`, `limitTargetType` лежат
прямо на правиле. Список правил отсортирован по `code`, затем `version`, затем `ruleId`.
`operationTypes` — отсортированный список кодов (не пустой; правило матчит объединение перечисленных
типов). `limitTargetType` — `CARD | PHONE | ACCOUNT` или `null`.

### 3.1. `attributeSelector` — EXTENSION сверх §4.3 (единственное расширение)

Скелет §4.3 **не содержит** attribute-селектора на правиле. Это осознанное расширение (решение
заказчика, дизайн-документ `2026-07-07-critical-path-rule-reshape-design.md`, §1.1). Контракт:

* `attributeSelector` **всегда присутствует** как объект `{type, value}`; когда правило не сужено —
  `type = "NONE"`, `value = null`.
* `type` ∈ `NONE | PAYMENT_SYSTEM | ISSUER_COUNTRY | BIN | BANK | CARD_TYPE | CARD_LEVEL`.
* `value` — строка-значение атрибута (например, код платёжной системы), `null` при `type = NONE`.
* **Engine, ещё не умеющий сужение по атрибуту, должен игнорировать это поле** (трактовать правило так,
  будто селектора нет) и **не** отвергать манифест из-за его наличия — это опциональное расширение.
  Engine с поддержкой сужения обязан фильтровать по нему при `type != NONE`.

### 3.2. `measure` и деньги (строкой)

`metric` ∈ `AMOUNT | COUNT | INTERVAL`; `period` ∈ `DAY | WEEK | MONTH | PER_OPERATION` (nullable);
`aggregationScope` ∈ `OWNER | TARGET` (nullable); `currency` — ISO-код или `null`; `intervalMinutes` —
целое >0 только при `metric = INTERVAL`, иначе `null`.

`limitValue` сериализуется **строкой** (`BigDecimal.toPlainString()`, напр. `"1000.00"`) —
соответствует конвенции PAY_ALL «денежные суммы строками». Для правил `metric = INTERVAL` (нет
числового лимита) `limitValue = null`. **Engine должен парсить `limitValue` как decimal произвольной
точности, не как double/float.**

## 4. `assignments[]` — все уровни, включая GLOBAL

```json
[
  {
    "assignmentId": "7a3bd1a0-0000-4000-8000-000000000003",
    "ruleId": "0d9f1c2e-0000-4000-8000-000000000001",
    "owner": {"level": "GLOBAL", "id": null},
    "mode": "LIMITED",
    "activeFrom": "2026-07-01T00:00:00Z",
    "activeTo": null
  },
  {
    "assignmentId": "7a3bd1a0-0000-4000-8000-000000000002",
    "ruleId": "0d9f1c2e-0000-4000-8000-000000000001",
    "owner": {"level": "MERCHANT", "id": "502118"},
    "mode": "LIMITED",
    "activeFrom": "2026-07-05T00:00:00Z",
    "activeTo": null
  }
]
```

`owner.level` ∈ `GLOBAL | MERCHANT_GROUP | MERCHANT`. `owner.id` — `null` для `GLOBAL`, group UUID
(строкой) для `MERCHANT_GROUP`, merchant id (строкой) для `MERCHANT`. `mode` ∈
`LIMITED | UNLIMITED | BLOCKED`. `activeFrom`/`activeTo` — период действия (`activeTo = null` =
бессрочно); темпоральную выборку «что действует сейчас» делает engine. Внутреннее денормализованное
поле `ruleCode` на wire **не выводится** — engine связывает назначение с правилом по `ruleId`.

Список отсортирован по (внутр.) `ruleCode`, затем `owner.level`, затем `owner.id` (null — первым),
затем `assignmentId`.

## 5. `memberships[]`

```json
{
  "membershipId": "c1d2e3f4-0000-4000-8000-000000000004",
  "groupId": "bbbb2222-0000-4000-8000-000000000006",
  "merchantId": "502118",
  "activeFrom": "2026-07-10T00:00:00Z",
  "activeTo": null
}
```

`activeFrom`/`activeTo` — период членства. Поле `groupTypeId` на wire **не выводится** (используется
только внутри management на компиляции). Список отсортирован по `merchantId`, затем (внутр.)
`groupTypeId`, затем `activeFrom`, затем `membershipId`.

## 6. Реальный пример канонического JSON (для checksum) и checksum

Пример сгенерирован напрямую из продовых классов (`ManifestDocumentV2Mapper` +
`RuntimeManifestCanonicalJson`) на representative-данных выше (один COUNT-rule с TARGET-scope без
attribute-сужения — дневной лимит числа исходящих card-payout операций (`OCT`) на карту-получатель,
два assignment — GLOBAL и MERCHANT, одно membership, один operationType, `manifestVersion = 42`,
`effectiveFrom = 2026-07-10T00:00:00Z`). Ключи отсортированы по алфавиту на каждом уровне; ISO-даты;
явные null; `limitValue` — строка. **`id`/`checksum`/`status`/counts в хешируемый документ не входят.**

```json
{"assignments":[{"activeFrom":"2026-07-01T00:00:00Z","activeTo":null,"assignmentId":"7a3bd1a0-0000-4000-8000-000000000003","mode":"LIMITED","owner":{"id":null,"level":"GLOBAL"},"ruleId":"0d9f1c2e-0000-4000-8000-000000000001"},{"activeFrom":"2026-07-05T00:00:00Z","activeTo":null,"assignmentId":"7a3bd1a0-0000-4000-8000-000000000002","mode":"LIMITED","owner":{"id":"502118","level":"MERCHANT"},"ruleId":"0d9f1c2e-0000-4000-8000-000000000001"}],"businessTimezone":"Europe/Moscow","effectiveFrom":"2026-07-10T00:00:00Z","manifestVersion":42,"memberships":[{"activeFrom":"2026-07-10T00:00:00Z","activeTo":null,"groupId":"bbbb2222-0000-4000-8000-000000000006","membershipId":"c1d2e3f4-0000-4000-8000-000000000004","merchantId":"502118"}],"operationTypes":[{"code":"OCT","counterpartyType":"CARD","direction":"OUT"}],"rules":[{"attributeSelector":{"type":"NONE","value":null},"code":"PAYOUT-CARD-COUNT-DAY","direction":"OUT","errorMessageTemplate":"Limit exceeded: %d of %f (%s)","limitTargetType":"CARD","limitValue":"3","measure":{"aggregationScope":"TARGET","currency":null,"intervalMinutes":null,"metric":"COUNT","period":"DAY"},"operationTypes":["OCT"],"ruleId":"0d9f1c2e-0000-4000-8000-000000000001","version":2}],"schemaVersion":2}
```

```
checksum = sha256:8546c2090d819572eac60fa59b14da985a07c59644fc93bcdf3abe33db4ae168
```

Поля верхнего уровня документа в хеше (в алфавитном порядке): `assignments, businessTimezone,
effectiveFrom, manifestVersion, memberships, operationTypes, rules, schemaVersion`. Стабильность
checksum при перестановке коллекций на входе и его изменение при смене любого поля — под тестами
MGT-U-06/MGT-U-07 (`RuntimeManifestCompilerTest`), форма §4.3 — под `ManifestDocumentV2MapperTest`.

## 7. Как engine пересчитывает checksum (точный алгоритм)

1. Взять `data.document` из HTTP-ответа (именно вложенный объект, **без** обёрточных полей
   `id`/`checksum`/`status`/`ruleCount`/`assignmentCount`/`membershipCount`).
2. Сериализовать его в **канонический** JSON: ключи всех объектов (включая вложенные `owner`,
   `measure`, `attributeSelector`, элементы массивов) отсортированы по алфавиту; массивы — **в том
   порядке, в каком пришли** (management уже отсортировал детерминированно, переупорядочивать нельзя);
   Instant — ISO-8601 в UTC с суффиксом `Z`; null-поля сериализуются **явно** как `null` (не
   опускаются); строки/числа без лишних пробелов.
3. `sha256:` + hex(SHA-256(UTF-8 байты)).
4. Сравнить с `data.checksum`. Расхождение → манифест отвергается (fail-closed), остаётся действующим
   предыдущий, алерт.

Хешировать сырое тело HTTP-ответа нельзя — там порядок ключей другой (обёрточный mapper не сортирует) и
присутствуют обёрточные поля. Нужно построить каноническую форму документа по шагам выше.

## 8. `effectiveFrom` в прошлом → 400 (MGT-I-10)

`POST /runtime-manifests` (и `POST /runtime-manifests/{id}/rollback`) проверяют `effectiveFrom` против
`now + minActivationLeadTime` (конфиг `pay-limit-management.runtime-manifest.min-activation-lead-time`,
default `5m`). Нарушение — включая `effectiveFrom` строго в прошлом — даёт код
`RUNTIME_MANIFEST_LEAD_TIME_VIOLATION` и **HTTP 400**.

## 9. Контракт для engine (§4.5 сервисной спеки — сверено с кодом)

* **Verify-on-fetch:** после получения манифеста engine пересчитывает SHA-256 по каноническому виду
  `data.document` (§7) и сверяет с `data.checksum`. Расхождение → манифест отвергается, остаётся
  действующим предыдущий, алерт.
* **Неизвестная `schemaVersion` → fail-closed:** если `schemaVersion` документа не поддерживается
  engine — манифест отвергается **целиком**, engine продолжает на последнем принятом валидном
  манифесте, алерт. Не деградировать до «применить частично» или «как v1».
* **`If-None-Match`:** engine передаёт `If-None-Match: <ETag предыдущего ответа>` (значение —
  `"sha256:..."` в кавычках) на `GET .../effective` и `GET .../scheduled`; сервер сравнивает
  нормализованное значение (снимает `W/` и кавычки) с текущим checksum и отвечает `304 Not Modified` с
  пустым телом и тем же `ETag`, если манифест не изменился.
* **Rollback может понизить `schemaVersion`:** `POST /runtime-manifests/{id}/rollback` копирует
  содержимое манифеста-источника без изменений (меняются только `manifestVersion`, `createdAt`,
  `effectiveFrom`, `checksum`, `id`). Откат к манифесту, скомпилированному до вехи M1, отдаст engine
  документ со старым `schemaVersion` — обращаться как с любым `schemaVersion` (поддержать либо
  отвергнуть fail-closed).
* **`GET .../active` — алиас `GET .../effective`** (оба принимают `?at=` и `If-None-Match`, поведение
  идентично).
* **Порядок раскатки:** engine с поддержкой `schemaVersion = 2` должен быть live **до** первой
  компиляции v2-манифеста; иначе более старый engine обязан fail-closed отвергнуть неизвестную схему.

## 10. Что не входит в эту веху (не блокирует M1)

Этап 5 (аудит `X-Operator-Id`/`audit_event`), этап 6 (`effective-limits` + метрики → веха M2), этап 7
(сид первой группы) — вне скоупа и не влияют на форму манифеста.

---

**Публикация в wiki** (чтобы документ стал видим команде engine) выполняется оркестратором/пользователем
отдельно — этот файл фиксирует содержимое в репозитории.
