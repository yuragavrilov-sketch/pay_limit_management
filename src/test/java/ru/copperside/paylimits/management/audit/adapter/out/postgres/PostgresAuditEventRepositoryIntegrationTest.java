package ru.copperside.paylimits.management.audit.adapter.out.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.audit.domain.AuditEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PostgresAuditEventRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "limit_management");
        registry.add("spring.flyway.default-schema", () -> "limit_management");
    }

    @Autowired
    private PostgresAuditEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from limit_management.audit_event");
    }

    @Test
    void appendsAndFindsWithFilters() {
        AuditEvent ruleEvent = event("LIMIT_RULE", "RULE_A", "CREATE",
                Instant.parse("2026-07-01T10:00:00Z"), null, "{\"status\":\"DRAFT\"}");
        AuditEvent ruleUpdate = event("LIMIT_RULE", "RULE_A", "UPDATE",
                Instant.parse("2026-07-02T10:00:00Z"), "{\"status\":\"DRAFT\"}", "{\"status\":\"ACTIVE\"}");
        AuditEvent otherEntity = event("MERCHANT_GROUP", "GROUP_X", "CREATE",
                Instant.parse("2026-07-01T11:00:00Z"), null, "{\"name\":\"x\"}");
        repository.append(ruleEvent);
        repository.append(ruleUpdate);
        repository.append(otherEntity);

        List<AuditEvent> byEntity = repository.find("LIMIT_RULE", "RULE_A", null, null, 0, 50);
        assertThat(byEntity).extracting(AuditEvent::action).containsExactly("UPDATE", "CREATE");

        List<AuditEvent> byType = repository.find("LIMIT_RULE", null, null, null, 0, 50);
        assertThat(byType).hasSize(2);

        List<AuditEvent> all = repository.find(null, null, null, null, 0, 50);
        assertThat(all).hasSize(3);

        List<AuditEvent> byWindow = repository.find(null, null,
                Instant.parse("2026-07-02T00:00:00Z"), null, 0, 50);
        assertThat(byWindow).extracting(AuditEvent::entityId).containsExactly("RULE_A");
    }

    @Test
    void roundTripsBeforeAfterJsonAndNulls() {
        UUID id = UUID.randomUUID();
        repository.append(new AuditEvent(
                id, "LIMIT_RULE", "RULE_JSON", "CREATE", "op-001", "Jane",
                Instant.parse("2026-07-01T10:00:00Z"), null, "{\"status\":\"DRAFT\"}"));

        AuditEvent found = repository.find("LIMIT_RULE", "RULE_JSON", null, null, 0, 50).get(0);

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.actorId()).isEqualTo("op-001");
        assertThat(found.actorName()).isEqualTo("Jane");
        assertThat(found.beforeJson()).isNull();
        assertThat(found.afterJson()).contains("\"status\"").contains("DRAFT");
    }

    @Test
    void paginatesByPageAndSize() {
        for (int i = 0; i < 5; i++) {
            repository.append(event("LIMIT_RULE", "RULE_PAGE", "TICK",
                    Instant.parse("2026-07-01T10:0" + i + ":00Z"), null, null));
        }

        List<AuditEvent> firstPage = repository.find("LIMIT_RULE", "RULE_PAGE", null, null, 0, 2);
        List<AuditEvent> secondPage = repository.find("LIMIT_RULE", "RULE_PAGE", null, null, 1, 2);

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(2);
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
    }

    private AuditEvent event(String entityType, String entityId, String action,
                             Instant occurredAt, String before, String after) {
        return new AuditEvent(
                UUID.randomUUID(), entityType, entityId, action, "op-001", "Operator",
                occurredAt, before, after);
    }
}
