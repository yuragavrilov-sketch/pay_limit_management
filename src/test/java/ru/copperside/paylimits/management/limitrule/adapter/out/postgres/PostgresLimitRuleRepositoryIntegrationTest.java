package ru.copperside.paylimits.management.limitrule.adapter.out.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PostgresLimitRuleRepositoryIntegrationTest {

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
    private PostgresLimitRuleRepository repository;

    @Test
    void listsSeededOperationTypes() {
        List<OperationType> types = repository.listOperationTypes();

        assertThat(types).extracting(OperationType::code).contains("SBP_C2B", "SBP_B2C");
    }

    @Test
    void savesAndActivatesVersionedRule() {
        OperationType type = repository.listOperationTypes().stream()
                .filter(operationType -> operationType.code().equals("SBP_C2B"))
                .findFirst()
                .orElseThrow();

        LimitRule draft = new LimitRule(
                UUID.randomUUID(),
                "RULE_SBP_C2B_DAY",
                1,
                "SBP C2B daily amount",
                type.id(),
                type.code(),
                type.direction(),
                "PHONE",
                RuleMetric.AMOUNT,
                RulePeriod.DAY,
                "RUB",
                RuleStatus.DRAFT,
                Instant.parse("2026-05-27T09:00:00Z"),
                Instant.parse("2026-05-27T09:00:00Z"),
                null,
                null
        );

        repository.saveRule(draft);
        LimitRule active = repository.updateRule(new LimitRule(
                draft.id(), draft.code(), draft.version(), draft.name(), draft.operationTypeId(),
                draft.operationTypeCode(), draft.direction(), draft.targetType(), draft.metric(), draft.period(),
                draft.currency(), RuleStatus.ACTIVE, draft.createdAt(), Instant.parse("2026-05-27T10:00:00Z"),
                Instant.parse("2026-05-27T10:00:00Z"), null
        ));

        assertThat(repository.findRule(draft.id())).contains(active);
        assertThat(repository.findActiveByCode(draft.code())).contains(active);
        assertThat(repository.hasActiveRulesForOperationType(type.id())).isTrue();
    }

    @Test
    void nextVersionUsesPersistedRuleVersions() {
        OperationType type = repository.listOperationTypes().stream()
                .filter(operationType -> operationType.code().equals("SBP_B2C"))
                .findFirst()
                .orElseThrow();
        Instant now = Instant.parse("2026-05-27T09:00:00Z");

        repository.saveRule(rule(type, "RULE_SBP_B2C_COUNT_WEEK", 1, RuleStatus.DISABLED, RuleMetric.COUNT, null, now));
        repository.saveRule(rule(type, "RULE_SBP_B2C_COUNT_WEEK", 2, RuleStatus.DRAFT, RuleMetric.COUNT, null, now));

        assertThat(repository.nextVersion("RULE_SBP_B2C_COUNT_WEEK")).isEqualTo(3);
        assertThat(repository.nextVersion("RULE_NEW")).isEqualTo(1);
    }

    @Test
    void mapsDuplicateDraftRuleToProblemCode() {
        OperationType type = repository.listOperationTypes().stream()
                .filter(operationType -> operationType.code().equals("SBP_C2B"))
                .findFirst()
                .orElseThrow();
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        repository.saveRule(rule(type, "RULE_SBP_C2B_COUNT_DAY", 1, RuleStatus.DRAFT, RuleMetric.COUNT, null, now));

        assertThatThrownBy(() -> repository.saveRule(
                rule(type, "RULE_SBP_C2B_COUNT_DAY", 2, RuleStatus.DRAFT, RuleMetric.COUNT, null, now)))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_DRAFT_EXISTS");
    }

    @Test
    void mapsInvalidRuleDefinitionToProblemCode() {
        OperationType type = repository.listOperationTypes().stream()
                .filter(operationType -> operationType.code().equals("SBP_C2B"))
                .findFirst()
                .orElseThrow();
        Instant now = Instant.parse("2026-05-27T09:00:00Z");

        assertThatThrownBy(() -> repository.saveRule(
                rule(type, "RULE_SBP_C2B_COUNT_MONTH", 1, RuleStatus.DRAFT, RuleMetric.COUNT, "RUB", now)))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("INVALID_RULE_DEFINITION");
    }

    private LimitRule rule(
            OperationType type,
            String code,
            int version,
            RuleStatus status,
            RuleMetric metric,
            String currency,
            Instant now
    ) {
        Instant activatedAt = status == RuleStatus.DRAFT ? null : now;
        Instant disabledAt = status == RuleStatus.DISABLED ? now.plusSeconds(60) : null;
        return new LimitRule(
                UUID.randomUUID(),
                code,
                version,
                code,
                type.id(),
                type.code(),
                type.direction(),
                "PHONE",
                metric,
                RulePeriod.DAY,
                currency,
                status,
                now,
                now,
                activatedAt,
                disabledAt
        );
    }
}
