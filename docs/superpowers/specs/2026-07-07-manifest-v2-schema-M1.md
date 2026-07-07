---
title: pay_limit_management — runtime-манифест v2, фактическая схема для engine (веха M1)
date: 2026-07-07
status: as-built (сверено с кодом на feat/manifest-v2)
scope: этап 4 (манифест v2), задача 3 — итоговая документация схемы для команды engine
sources:
  - Код: RuntimeManifestPayload, RuntimeManifest, RuntimeManifestResponse, RuntimeCompiledRule,
    RuntimeCompiledAssignment, RuntimeMerchantGroupMembership, RuntimeOperationType,
    RuntimeManifestCanonicalJson, RuntimeManifestCompiler, RuntimeManifestController,
    GlobalExceptionHandler (все — `src/main/java/.../runtimeconfig/**`, `.../common/web/**`)
  - Спека сервиса 1f9a4b6c-9ff2-4a09-b510-061838e70d7d, §3.1/§4.1–4.5 (skeleton-версия, частично
    разошлась с кодом — расхождения отмечены ниже явно)
  - Техспека 37462b5e-da37-4a88-9463-3e406ab3e785, §2 (доменная модель, attributeSelector)
---

Этот документ описывает **фактическую** форму runtime-манифеста v2, как его реально эмитит код
`pay_limit_management` на момент вехи M1 (ветка `feat/manifest-v2`). Там, где код разошёлся со
скелетом §4.3 спеки сервиса, это явно помечено — код здесь является источником истины для engine
(приоритет источников по общему CLAUDE.md — Постановка > техспека > сервисная спека > код,
но §4.3 — иллюстративный скелет, а не машиночитаемый контракт; машиночитаемый контракт — Swagger
из кода, см. §3 внизу).

## 0. Две разные JSON-формы — не путать

Существуют **два разных** сериализованных представления одних и тех же данных манифеста:

1. **Wire-форма (HTTP-ответ)** — то, что реально возвращают эндпоинты `GET/POST
   /internal/v1/limit-management/runtime-manifests/**`: `RuntimeManifestResponse`, обёрнутый в
   `ApiResponse{data, meta?, error, timestamp}`. Сериализуется дефолтным Jackson-мэппером
   Spring Boot (порядок ключей — порядок объявления полей записи, **не** отсортирован; `meta`
   опущен при null, `error` всегда присутствует и равен `null` при успехе). Именно это тело engine
   получает по HTTP.
2. **Канонический JSON (только для checksum)** — внутреннее представление `RuntimeManifestPayload`,
   которое `RuntimeManifestCanonicalJson` сериализует через отдельный Jackson `ObjectMapper` с
   `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY` + `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`
   (ключи объектов и мап отсортированы по алфавиту **на каждом уровне вложенности**, включая
   вложенные объекты типа `matcher`/`measure`). Это представление **никогда не отдаётся по HTTP как
   есть** — оно существует только чтобы посчитать `checksum = "sha256:" + hex(SHA-256(байты))`.

**Важно для engine:** чтобы пересчитать и проверить `checksum`, недостаточно захешировать сырое тело
HTTP-ответа — порядок ключей там другой. Engine должен самостоятельно построить канонический вид
(см. §5) из полученных полей и захешировать его, либо — вариант проще для engine — трактовать
`checksum` как непрозрачный opaque-токен для detection изменений (`If-None-Match`) без байт-в-байт
пересчёта. Полный байт-в-байт пересчёт (для defence-in-depth, §4.5 сервисной спеки) требует повторить
алгоритм канонизации §5 один в один.

## 1. Верхний уровень (wire-форма, `RuntimeManifestResponse` внутри `data`)

```
{
  "id": "<uuid>",
  "schemaVersion": 2,
  "businessTimezone": "Europe/Moscow",
  "operationTypes": [ ... §2 ... ],
  "version": 42,
  "status": "VALID",
  "checksum": "sha256:<64 hex chars>",
  "createdAt": "2026-07-06T12:00:00Z",
  "effectiveFrom": "2026-07-10T00:00:00Z",
  "ruleCount": 1,
  "assignmentCount": 2,
  "membershipCount": 1,
  "rules": [ ... §3 ... ],
  "assignments": [ ... §4 ... ],
  "memberships": [ ... §5 ... ],
  "diagnostics": []
}
```

Поля верхнего уровня — 1:1 из `RuntimeManifest`/`RuntimeManifestResponse`:

| Поле | Тип | Заметки |
|---|---|---|
| `id` | UUID | id манифеста в БД management; **не входит** в checksum-хеш (см. §5) |
| `schemaVersion` | int | сейчас всегда `2` для свежескомпилированных манифестов. **Rollback — исключение**: см. §6 |
| `businessTimezone` | string (IANA zone id) | зафиксирован в манифесте на момент компиляции; смена конфига действует со следующей компиляции |
| `operationTypes` | array | справочник типов операций, см. §2 |
| `version` | int | **не** `manifestVersion` — в коде и API поле называется `version` (расхождение со скелетом §4.3 спеки, который называет его `manifestVersion`) |
| `status` | string enum | единственное текущее значение — `"VALID"` (`RuntimeManifestStatus` enum содержит только `VALID`; поле зарезервировано на будущее) |
| `checksum` | string | `"sha256:" + hex` |
| `createdAt` | ISO-8601 Instant (UTC, `Z`) | момент компиляции |
| `effectiveFrom` | ISO-8601 Instant (UTC, `Z`) | момент вступления в силу; на компиляции проверяется лид-тайм, см. §7 |
| `ruleCount`, `assignmentCount`, `membershipCount` | int | размер соответствующих коллекций — избыточные поля для быстрой проверки без парсинга массивов |
| `rules` | array | см. §3 |
| `assignments` | array | см. §4, все три уровня владельца, включая GLOBAL |
| `memberships` | array | см. §5 |
| `diagnostics` | array | на сегодня **компилятор всегда отдаёт `[]`** — `ManifestDiagnostic{code, severity, message, ruleIds[], path}` определён в домене, но ничего в него не пишет; engine может игнорировать/тихо принимать пустой массив |

Пустая конфигурация (нет активных правил) — валидный манифест с `rules: [], assignments: [],
memberships: [], ruleCount: 0, ...` (MGT-I-11).

## 2. `operationTypes[]`

```json
{"code": "OCT", "direction": "OUT", "counterpartyType": "CARD"}
```

`direction` ∈ `IN | OUT`. `counterpartyType` ∈ `CARD | PHONE | ACCOUNT`. Список отсортирован по `code`
на компиляции.

## 3. `rules[]` — полная форма (включая extension-поле `attribute`)

```json
{
  "ruleId": "0d9f1c2e-0000-4000-8000-000000000001",
  "code": "PAYOUT-CARD-COUNT-DAY",
  "version": 2,
  "matcher": {
    "operationTypes": ["OCT"],
    "direction": "OUT",
    "attribute": {"type": "NONE", "value": null},
    "targetType": "CARD"
  },
  "measure": {
    "metric": "COUNT",
    "period": "DAY",
    "aggregationScope": "TARGET",
    "currency": null,
    "intervalMinutes": null
  },
  "limitValue": 3,
  "errorMessageTemplate": "Превышен ежедневный лимит выплат на карту. Лимит %d, использовано %f, осталось количество выплат %s."
}
```

Список правил отсортирован по `code`, затем `version`, затем `ruleId` (стабильность для checksum).
`matcher.operationTypes` — отсортированный список кодов (не пустой; правило матчит объединение
перечисленных типов операций).

### 3.1 `matcher.attribute` — EXTENSION сверх скелета §4.3 спеки

Скелет §4.3 сервисной спеки **не содержит** поля attribute-селектора на правиле вообще. Это
осознанное расширение поверх спеки (решение заказчика, зафиксировано в дизайн-документе
`docs/superpowers/specs/2026-07-07-critical-path-rule-reshape-design.md`, §1.1: «attributeSelector —
сохраняем как расширение»). Контракт для engine:

* `attribute` **всегда присутствует** как объект `{type, value}` — даже когда правило не сужено по
  атрибуту: в этом случае `type = "NONE"`, `value = null`.
* `type` ∈ `NONE | PAYMENT_SYSTEM | ISSUER_COUNTRY | BIN | BANK | CARD_TYPE | CARD_LEVEL`
  (`AttributeSelectorType` enum).
* `value` — строка-значение атрибута (например, код платёжной системы), `null` при `type = NONE`.
* **Engine, ещё не умеющий сужение по атрибуту, должен игнорировать это поле** (трактовать правило
  так, будто `attribute` отсутствует) и **не** отвергать манифест из-за его наличия — это опциональное
  расширение, а не изменение обязательной части схемы. Engine, поддерживающий сужение, обязан
  фильтровать по нему, когда `type != NONE`.

### 3.2 `measure` и деньги

`metric` ∈ `AMOUNT | COUNT | INTERVAL`; `period` ∈ `DAY | WEEK | MONTH | PER_OPERATION`;
`aggregationScope` ∈ `OWNER | TARGET`; `currency` — ISO-код или `null` (не задан для COUNT-метрик в
примере выше); `intervalMinutes` — целое >0 только при `metric = INTERVAL`, иначе `null`.

**Расхождение с общей конвенцией PAY_ALL (сервисная спека §3.1 — «денежные суммы строками»):**
`limitValue` в манифесте сериализуется как **обычное JSON-число** (`BigDecimal` через стандартный
Jackson `NumberSerializer`, без строковой обёртки), а не строка `"1000.00"`. Это фактическое поведение
кода на текущей ветке — не идеализированная спека. **Engine должен парсить `limitValue` как
произвольную точность (`BigDecimal`/decimal), а не `double`/`float`**, чтобы не терять точность на
JSON-числе с дробной частью. Если для engine критично строковое представление — это отдельный запрос
на доработку management, вне скоупа этой вехи.

## 4. `assignments[]` — все уровни, включая GLOBAL

```json
[
  {
    "assignmentId": "7a3bd1a0-0000-4000-8000-000000000003",
    "ruleId": "0d9f1c2e-0000-4000-8000-000000000001",
    "ruleCode": "PAYOUT-CARD-COUNT-DAY",
    "ownerType": "GLOBAL",
    "ownerId": null,
    "limitMode": "LIMITED",
    "validFrom": "2026-07-01T00:00:00Z",
    "validTo": null
  },
  {
    "assignmentId": "7a3bd1a0-0000-4000-8000-000000000002",
    "ruleId": "0d9f1c2e-0000-4000-8000-000000000001",
    "ruleCode": "PAYOUT-CARD-COUNT-DAY",
    "ownerType": "MERCHANT_GROUP",
    "ownerId": "bbbb2222-0000-4000-8000-000000000006",
    "limitMode": "LIMITED",
    "validFrom": "2026-07-10T00:00:00Z",
    "validTo": null
  }
]
```

`ownerType` ∈ `GLOBAL | MERCHANT_GROUP | MERCHANT`. `ownerId` — `null` для `GLOBAL`, group UUID (как
строка) для `MERCHANT_GROUP`, merchant id (строка) для `MERCHANT`. `limitMode` ∈
`LIMITED | UNLIMITED | BLOCKED`. `validFrom`/`validTo` — период действия назначения (`validTo = null`
= бессрочно); темпоральную выборку «что действует сейчас» делает engine по этим полям, а не
management на компиляции.

**Расхождение с именами полей в скелете §4.3 спеки:** спека показывает вложенный `owner: {level, id}`
и имена `mode`/`activeFrom`/`activeTo`. Код отдаёт **плоские** поля `ownerType`/`ownerId`/`limitMode`
и `validFrom`/`validTo`. Также код добавляет удобное поле `ruleCode` (денормализация кода правила —
чтобы engine не искал по `ruleId` в массиве `rules` лишний раз). Engine должен ориентироваться на
реальные имена из этого документа, не на скелет спеки.

Список отсортирован по `ruleCode`, затем `ownerType`, затем `ownerId` (null — первым), затем
`assignmentId`.

## 5. `memberships[]`

```json
{
  "membershipId": "c1d2e3f4-0000-4000-8000-000000000004",
  "merchantId": "M42",
  "groupTypeId": "aaaa1111-0000-4000-8000-000000000005",
  "groupId": "bbbb2222-0000-4000-8000-000000000006",
  "validFrom": "2026-07-10T00:00:00Z",
  "validTo": null
}
```

Тоже плоская форма с `validFrom`/`validTo` (не `activeFrom`/`activeTo` из скелета §4.3), и
дополнительное поле `groupTypeId` (спека его не упоминает — денормализация типа группы для engine).
Список отсортирован по `merchantId`, затем `groupTypeId`, затем `validFrom`, затем `membershipId`.

## 6. Реальный пример канонического JSON (для checksum) и checksum

Пример сгенерирован напрямую из продовых классов (`RuntimeManifestPayload` +
`RuntimeManifestCanonicalJson`) с representative-данными выше (один rule без attribute-сужения, два
assignment — GLOBAL и MERCHANT_GROUP, одно membership, один operationType). Ключи отсортированы по
алфавиту на каждом уровне; ISO-даты; `limitValue` — число; **`id` и `checksum` в это представление не
входят** (они не часть хешируемого payload — `checksum` не может входить в данные, из которых он
вычислен, а `id` присваивается уже после подсчёта checksum):

```json
{"assignmentCount":2,"assignments":[{"assignmentId":"7a3bd1a0-0000-4000-8000-000000000003","limitMode":"LIMITED","ownerId":null,"ownerType":"GLOBAL","ruleCode":"PAYOUT-CARD-COUNT-DAY","ruleId":"0d9f1c2e-0000-4000-8000-000000000001","validFrom":"2026-07-01T00:00:00Z","validTo":null},{"assignmentId":"7a3bd1a0-0000-4000-8000-000000000002","limitMode":"LIMITED","ownerId":"bbbb2222-0000-4000-8000-000000000006","ownerType":"MERCHANT_GROUP","ruleCode":"PAYOUT-CARD-COUNT-DAY","ruleId":"0d9f1c2e-0000-4000-8000-000000000001","validFrom":"2026-07-10T00:00:00Z","validTo":null}],"businessTimezone":"Europe/Moscow","createdAt":"2026-07-06T12:00:00Z","diagnostics":[],"effectiveFrom":"2026-07-10T00:00:00Z","membershipCount":1,"memberships":[{"groupId":"bbbb2222-0000-4000-8000-000000000006","groupTypeId":"aaaa1111-0000-4000-8000-000000000005","membershipId":"c1d2e3f4-0000-4000-8000-000000000004","merchantId":"M42","validFrom":"2026-07-10T00:00:00Z","validTo":null}],"operationTypes":[{"code":"OCT","counterpartyType":"CARD","direction":"OUT"}],"ruleCount":1,"rules":[{"code":"PAYOUT-CARD-COUNT-DAY","errorMessageTemplate":"Превышен ежедневный лимит выплат на карту. Лимит %d, использовано %f, осталось количество выплат %s.","limitValue":3,"matcher":{"attribute":{"type":"NONE","value":null},"direction":"OUT","operationTypes":["OCT"],"targetType":"CARD"},"measure":{"aggregationScope":"TARGET","currency":null,"intervalMinutes":null,"metric":"COUNT","period":"DAY"},"ruleId":"0d9f1c2e-0000-4000-8000-000000000001","version":2}],"schemaVersion":2,"status":"VALID","version":42}
```

```
checksum = sha256:a9f6682477fc93fcad28e3a29d54415b259f35f63ea1a80b2b13a91ebed9fc0f
```

Поля, участвующие в хеше (весь `RuntimeManifestPayload`, ровно эти 14 полей верхнего уровня, в
алфавитном порядке ключей на каждом уровне): `assignmentCount, assignments, businessTimezone,
createdAt, diagnostics, effectiveFrom, membershipCount, memberships, operationTypes, ruleCount, rules,
schemaVersion, status, version`. **Не входят**: `id`, `checksum` (оба — часть `RuntimeManifest`/
`RuntimeManifestResponse`, добавляются поверх payload уже после подсчёта хеша).

Стабильность checksum при перестановке коллекций на входе — под тестами MGT-U-06/MGT-U-07
(`RuntimeManifestCompilerTest`).

## 7. `effectiveFrom` в прошлом → 400 (MGT-I-10)

`POST /runtime-manifests` (и `POST /runtime-manifests/{id}/rollback`) проверяют `effectiveFrom` против
`now + minActivationLeadTime` (конфиг
`pay-limit-management.runtime-manifest.min-activation-lead-time`, default `5m`). Нарушение — включая
случай `effectiveFrom` строго в прошлом — даёт код ошибки `RUNTIME_MANIFEST_LEAD_TIME_VIOLATION` и
**HTTP 400** (`ProblemEnvelope{error:{code,title,status,detail,...}}`).

> На момент начала этой задачи `GlobalExceptionHandler` ошибочно маппил
> `RUNTIME_MANIFEST_LEAD_TIME_VIOLATION` в HTTP 409 (общий `default → CONFLICT` для
> `RuntimeManifestProblemException`), что противоречило MGT-I-10 (спека, §8: «ожидаемый результат —
> 400») и DoD этапа 4. Исправлено в рамках этой задачи: код добавлен в ветку `BAD_REQUEST` наравне с
> `VALIDATION_ERROR`. Существующий тест переименован и поправлен
> (`mapsLeadTimeViolationToBadRequestProblem`), добавлен отдельный тест MGT-I-10
> (`mgtI10CompilingWithEffectiveFromInThePastReturnsBadRequest`) — оба зелёные.

## 8. Контракт для engine (§4.5 сервисной спеки — сверено с кодом)

* **Verify-on-fetch:** после получения манифеста engine пересчитывает SHA-256 по каноническому виду
  payload-полей (§6) и сверяет с `checksum`. Расхождение → манифест отвергается, остаётся действующим
  предыдущий, алерт. Если полный байт-в-байт пересчёт не оправдан трудозатратами на стороне engine —
  минимально допустимая альтернатива: TLS-канал до management как единственная защита целостности, а
  `checksum`/`If-None-Match` использовать только для polling-дедупликации (без криптографической
  верификации) — это ослабление нужно явно согласовать, т.к. официальный контракт спеки предполагает
  пересчёт.
* **Неизвестная `schemaVersion` → fail-closed:** если `schemaVersion` манифеста, отданного
  `/effective` или `/scheduled`, не поддерживается engine — манифест отвергается **целиком**, engine
  продолжает работать на последнем принятом (валидном) манифесте, алерт. Не деградировать до
  «применить частично» или «применить как v1».
* **`If-None-Match`:** engine передаёт `If-None-Match: <ETag предыдущего ответа>` (значение —
  `"sha256:..."` в кавычках, как обычный HTTP ETag) на `GET .../effective` и `GET .../scheduled`;
  сервер сравнивает нормализованное значение (снимает `W/` и кавычки) с текущим checksum и отвечает
  `304 Not Modified` с пустым телом и тем же `ETag`, если манифест не изменился — так polling каждые
  30 с не гоняет полное тело при отсутствии изменений.
* **Порядок раскатки:** engine с поддержкой `schemaVersion = 2` должен быть задеплоен и live **до**
  первой компиляции манифеста v2 в management. Иначе engine, ещё не понимающий v2, обязан fail-closed
  отвергнуть такой манифест (см. выше) — но если он этого не умеет (более старая версия engine без
  fail-closed на неизвестную схему), возможна рассинхронизация. Порядок раскатки — организационное
  требование, не техническая защита.
* **Rollback может понизить `schemaVersion`:** `POST /runtime-manifests/{id}/rollback` копирует
  `schemaVersion`, `businessTimezone`, `operationTypes`, `rules`, `assignments`, `memberships`,
  `diagnostics` **из манифеста-источника без изменений** (меняются только `version`, `createdAt`,
  `effectiveFrom`, `checksum`, `id`). Если оператор откатится к манифесту, скомпилированному до вехи
  M1, engine получит манифест со старым `schemaVersion` — с ним нужно обращаться так же, как с любым
  другим `schemaVersion`, который engine либо поддерживает, либо отвергает fail-closed.
* **`GET .../active` — алиас `GET .../effective`** (оба принимают `?at=` и `If-None-Match`,
  поведение идентично); используйте любой, они не расходятся.

## 9. Что не входит в эту веху (не блокирует M1)

Этап 5 (аудит `X-Operator-Id`/`audit_event`), этап 6 (`effective-limits` + метрики → веха M2), этап 7
(сид первой группы) — вне скоупа этого документа и не влияют на форму манифеста, описанную выше.

---

**Публикация в wiki** (чтобы документ стал видим команде engine и синхронизировался с деревом спек)
выполняется оркестратором/пользователем отдельно — этот файл только фиксирует содержимое в репозитории.
