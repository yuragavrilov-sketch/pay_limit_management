# pay-limit-management

Administrative service skeleton for payment limits configuration.

## Scope

This first increment provides the Spring Boot service shell, configuration wiring, actuator health endpoint, OpenAPI UI wiring, Docker build, and configuration property validation. It does not include domain controllers, persistence, or limit-management business logic yet.

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
| `VAULT_KV_CONTEXTS` | empty | Vault application contexts |
| `PAY_LIMIT_MANAGEMENT_SERVICE_NAME` | `Limit Management` | Display name for this service |

## Run

```powershell
mvn spring-boot:run
```

Health: [http://localhost:8084/actuator/health](http://localhost:8084/actuator/health)

OpenAPI:

- [http://localhost:8084/v3/api-docs](http://localhost:8084/v3/api-docs)
- [http://localhost:8084/swagger-ui.html](http://localhost:8084/swagger-ui.html)

Contract: [../contracts/pay-limit-management](../contracts/pay-limit-management)

## Tests

```powershell
mvn clean verify
```
