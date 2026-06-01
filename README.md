# pay-limit-management

Administrative service for payment limits configuration.

## Scope

The service exposes limit catalog and rule administration APIs, stores data in
Postgres schema `limit_management`, runs Flyway migrations, and follows the
PAY_ALL Config Server + Vault configuration model.

## Stack

- Java 21
- Spring Boot 4.0.6
- Spring Cloud 2025.1.1
- Spring MVC, Validation, Actuator
- Spring Cloud Config Client and Vault Config
- springdoc-openapi

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8084` | HTTP port |
| `PAY_ENVIRONMENT` | `local` | PAY_ALL environment label |
| `CONFIG_SERVER_ENABLED` | `false` locally, `true` in test/prod | Enables Spring Cloud Config |
| `CONFIG_SERVER_URL` | `http://pay-config:8080` | Config Server URL |
| `CONFIG_SERVER_LABEL` | `${pay.environment}` | Config Server Git label |
| `VAULT_ENABLED` | `false` locally, `true` in test/prod | Enables Vault Config |
| `VAULT_KV_BACKEND` | `pay` | Vault KV backend |
| `VAULT_KV_CONTEXTS` | `${PAY_ENVIRONMENT}/pay-limit-management-db-password` | Vault application contexts |
| `PAY_LIMIT_MANAGEMENT_DB_URL` | `jdbc:postgresql://localhost:5432/pay_admin?currentSchema=limit_management` | Local fallback DB URL |
| `PAY_LIMIT_MANAGEMENT_DB_USERNAME` | `pay_admin` | Local fallback DB user |
| `PAY_LIMIT_MANAGEMENT_DB_PASSWORD` | empty | Local fallback DB password; test/profile runs load it from Vault |
| `PAY_LIMIT_MANAGEMENT_SERVICE_NAME` | `Limit Management` | Display name for this service |
| `PAY_LIMIT_MANAGEMENT_RUNTIME_MANIFEST_MIN_ACTIVATION_LEAD_TIME` | `5m` | Minimum accepted delay between runtime manifest creation and `effectiveFrom` |

## Run

Local standalone run keeps Config Server and Vault disabled and uses local
defaults or `PAY_LIMIT_MANAGEMENT_DB_*` environment overrides:

```powershell
mvn spring-boot:run
```

## Rule manifests

`POST /internal/v1/limit-management/rule-manifests` compiles all `ACTIVE`
rules into a versioned manifest for future `pay-limit-engine` consumption.

The compiler stores only valid manifests. It rejects duplicate matcher/measure
definitions, ambiguous `ANY`/`FAMILY`/`TYPE` operation overlaps, disabled or
missing dictionary values, and incompatible operation type directions.

Read endpoints:

- `GET /internal/v1/limit-management/rule-manifests/latest`
- `GET /internal/v1/limit-management/rule-manifests/{manifestId}`

## Runtime manifests

Runtime manifests are immutable engine-facing snapshots with mandatory
`effectiveFrom`. They include active rules, enabled assignments, and merchant
group memberships, and the checksum covers the canonical payload including
`effectiveFrom`. Runtime rule matchers also include engine-ready operation
matching fields: `ANY` selectors set `operationMatchesAll=true`, `TYPE`
selectors publish the selected operation type code, and `FAMILY` selectors are
expanded to concrete enabled operation type codes before publishing.

Endpoints:

- `GET /internal/v1/limit-management/runtime-manifests?at={instant}&limit={n}`
- `POST /internal/v1/limit-management/runtime-manifests`
- `GET /internal/v1/limit-management/runtime-manifests/active?at={instant}`
- `GET /internal/v1/limit-management/runtime-manifests/effective?at={instant}`
- `GET /internal/v1/limit-management/runtime-manifests/scheduled?after={instant}&limit={n}`
- `GET /internal/v1/limit-management/runtime-manifests/{manifestId}`
- `POST /internal/v1/limit-management/runtime-manifests/{manifestId}/rollback`

Compilation rejects requests where `effectiveFrom` is earlier than
`now + pay-limit-management.runtime-manifest.min-activation-lead-time`.
Lifecycle history derives `SCHEDULED`, `ACTIVE`, and `SUPERSEDED` at the
requested `at` instant. Rollback creates a new immutable runtime manifest
version from an older payload and a new `effectiveFrom`.

Full test-profile startup is owned by `../infra/run-test.ps1`: non-secret
database config is loaded from Config Server branch `test`, and
`spring.datasource.password` is loaded from Vault path
`pay/test/pay-limit-management-db-password`.

Health: [http://localhost:8084/actuator/health](http://localhost:8084/actuator/health)

## Limit assignments

Assignments bind an `ACTIVE` rule to either a merchant group or a merchant:

- `GET /internal/v1/limit-management/assignments`
- `POST /internal/v1/limit-management/assignments`
- `PATCH /internal/v1/limit-management/assignments/{assignmentId}`
- `POST /internal/v1/limit-management/assignments/{assignmentId}/disable`

`ruleId`, `ownerType`, and `ownerId` are immutable after creation. Enabled
assignments for the same rule and owner must not overlap by
`validFrom`/`validTo`. `LIMITED` assignments require `limitValue`;
`UNLIMITED` and `BLOCKED` assignments keep `limitValue` null.

### Docker Compose contour

The full local contour is owned by `../infra/docker-compose.yaml`.

```powershell
cd ..\infra
docker compose up -d --build pay-limit-management
```

The container uses `SPRING_PROFILES_ACTIVE=compose`, Config Server label
`compose`, Postgres at `postgres:5432`, and Vault secret
`pay/compose/pay-limit-management-db-password`.

OpenAPI:

- [http://localhost:8084/v3/api-docs](http://localhost:8084/v3/api-docs)
- [http://localhost:8084/swagger-ui.html](http://localhost:8084/swagger-ui.html)

Contract: [../contracts/pay-limit-management](../contracts/pay-limit-management)

## Tests

```powershell
mvn clean verify
```
