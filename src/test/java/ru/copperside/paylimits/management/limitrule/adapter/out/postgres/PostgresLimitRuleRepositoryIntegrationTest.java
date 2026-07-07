package ru.copperside.paylimits.management.limitrule.adapter.out.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.DictionaryItem;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    void listsRuleDictionaries() {
        RuleDictionaries dictionaries = repository.getRuleDictionaries();

        assertThat(dictionaries.operationFamilies()).extracting(DictionaryItem::code).contains("SBP", "CARD");
        assertThat(dictionaries.operationTypes()).extracting(OperationType::code)
                .contains("SBP_C2B", "SBP_B2C", "ECOM", "AFT", "OCT");
        assertThat(dictionaries.paymentSystems()).extracting(DictionaryItem::code).contains("MIR", "VISA");
        assertThat(dictionaries.attributeSelectorTypes()).contains(AttributeSelectorType.PAYMENT_SYSTEM);
        assertThat(dictionaries.aggregationScopes()).contains(AggregationScope.OWNER, AggregationScope.TARGET);
    }

    @Test
    void listsSeededOperationTypes() {
        List<OperationType> types = repository.listOperationTypes();

        assertThat(types).extracting(OperationType::code).contains("SBP_C2B", "SBP_B2C");
        assertThat(types).allSatisfy(type -> assertThat(type.sortOrder()).isGreaterThanOrEqualTo(0));
    }

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

    @Test
    void savesAndActivatesVersionedRule() {
        LimitRule draft = rule(
                Set.of("SBP_C2B"),
                OperationDirection.IN,
                new RuleSelector<>(AttributeSelectorType.NONE, null),
                "RULE_SBP_C2B_DAY",
                1,
                RuleStatus.DRAFT,
                RuleMetric.AMOUNT,
                Instant.parse("2026-05-27T09:00:00Z")
        );

        repository.saveRule(draft);
        LimitRule active = repository.updateRule(new LimitRule(
                draft.id(), draft.code(), draft.version(), draft.name(), draft.operationTypes(),
                draft.direction(), draft.measure(), draft.limitTargetType(), draft.limitValue(),
                draft.errorMessageTemplate(), draft.attributeSelector(),
                RuleStatus.ACTIVE, draft.createdAt(), Instant.parse("2026-05-27T10:00:00Z"),
                Instant.parse("2026-05-27T10:00:00Z"), null
        ));

        assertThat(repository.findRule(draft.id())).contains(active);
        assertThat(repository.findActiveByCode(draft.code())).contains(active);
        assertThat(repository.hasActiveRulesForOperationTypeCode("SBP_C2B")).isTrue();
    }

    @Test
    void savesMultiOperationTypeAndAttributeSelectorRule() {
        LimitRule draft = rule(
                Set.of("ECOM", "OCT"),
                OperationDirection.IN,
                new RuleSelector<>(AttributeSelectorType.PAYMENT_SYSTEM, "MIR"),
                "RULE_CARD_MIR_DAY",
                1,
                RuleStatus.DRAFT,
                RuleMetric.AMOUNT,
                Instant.parse("2026-05-27T09:00:00Z")
        );

        repository.saveRule(draft);

        LimitRule found = repository.findRule(draft.id()).orElseThrow();
        assertThat(found.operationTypes()).containsExactlyInAnyOrder("ECOM", "OCT");
        assertThat(found).isEqualTo(draft);
    }

    @Test
    void nextVersionUsesPersistedRuleVersions() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");

        repository.saveRule(rule(Set.of("SBP_B2C"), OperationDirection.OUT, noneSelector(),
                "RULE_SBP_B2C_COUNT_WEEK", 1, RuleStatus.DISABLED, RuleMetric.COUNT, now));
        repository.saveRule(rule(Set.of("SBP_B2C"), OperationDirection.OUT, noneSelector(),
                "RULE_SBP_B2C_COUNT_WEEK", 2, RuleStatus.DRAFT, RuleMetric.COUNT, now));

        assertThat(repository.nextVersion("RULE_SBP_B2C_COUNT_WEEK")).isEqualTo(3);
        assertThat(repository.nextVersion("RULE_NEW")).isEqualTo(1);
    }

    @Test
    void mapsDuplicateDraftRuleToProblemCode() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        repository.saveRule(rule(Set.of("SBP_C2B"), OperationDirection.IN, noneSelector(),
                "RULE_SBP_COUNT_DAY", 1, RuleStatus.DRAFT, RuleMetric.COUNT, now));

        assertThatThrownBy(() -> repository.saveRule(rule(Set.of("SBP_C2B"), OperationDirection.IN, noneSelector(),
                "RULE_SBP_COUNT_DAY", 2, RuleStatus.DRAFT, RuleMetric.COUNT, now)))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_DRAFT_EXISTS");
    }

    @Test
    void mapsInvalidRuleDefinitionToProblemCode() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        // AMOUNT rule without a limit value violates limit_rules_limit_value_chk.
        LimitRule invalid = new LimitRule(
                UUID.randomUUID(),
                "RULE_SBP_COUNT_MONTH",
                1,
                "RULE_SBP_COUNT_MONTH",
                Set.of("SBP_C2B"),
                OperationDirection.IN,
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                LimitTargetType.PHONE,
                null,
                "template",
                noneSelector(),
                RuleStatus.DRAFT,
                now,
                now,
                null,
                null
        );

        assertThatThrownBy(() -> repository.saveRule(invalid))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("INVALID_RULE_DEFINITION");
    }

    @Test
    void rejectsOwnerScopeRuleWithTargetTypeViaDbCheck() {
        // App-level validation 4 already forbids this combination (LimitRuleService); this test
        // proves the V13 defense-in-depth CHECK independently rejects it at the DB layer too, in
        // case a caller bypasses the service (e.g. a future direct repository write).
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        LimitRule ownerScopeWithTarget = new LimitRule(
                UUID.randomUUID(),
                "RULE_OWNER_SCOPE_WITH_TARGET",
                1,
                "RULE_OWNER_SCOPE_WITH_TARGET",
                Set.of("SBP_C2B"),
                OperationDirection.IN,
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                LimitTargetType.PHONE,
                new BigDecimal("1000.00"),
                "template",
                noneSelector(),
                RuleStatus.DRAFT,
                now,
                now,
                null,
                null
        );

        assertThatThrownBy(() -> repository.saveRule(ownerScopeWithTarget))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("INVALID_RULE_DEFINITION");
    }

    @Test
    void savesAndReadsBackIntervalRule() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        LimitRule draft = new LimitRule(
                UUID.randomUUID(),
                "RULE_SBP_B2C_INTERVAL",
                1,
                "RULE_SBP_B2C_INTERVAL",
                Set.of("SBP_B2C"),
                OperationDirection.OUT,
                new Measure(RuleMetric.INTERVAL, null, AggregationScope.TARGET, null, 15),
                LimitTargetType.PHONE,
                null,
                "template",
                noneSelector(),
                RuleStatus.DRAFT,
                now,
                now,
                null,
                null
        );

        repository.saveRule(draft);
        LimitRule found = repository.findRule(draft.id()).orElseThrow();

        assertThat(found.measure().metric()).isEqualTo(RuleMetric.INTERVAL);
        assertThat(found.measure().aggregationScope()).isEqualTo(AggregationScope.TARGET);
        assertThat(found.measure().intervalMinutes()).isEqualTo(15);
        assertThat(found.measure().period()).isNull();
        assertThat(found.limitValue()).isNull();
        assertThat(found.limitTargetType()).isEqualTo(LimitTargetType.PHONE);
        assertThat(found.operationTypes()).containsExactly("SBP_B2C");
        assertThat(found).isEqualTo(draft);
    }

    @Test
    void listRulesBatchesOperationTypesMatchingFindRule() {
        // Regression for the N+1 fix in PostgresLimitRuleRepository.listRules(): the batched
        // operationTypes must be set-identical (order is irrelevant — the manifest compiler sorts)
        // to what the still-per-rule findRule() query returns.
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        LimitRule ruleA = rule(Set.of("SBP_C2B", "SBP_B2C"), OperationDirection.IN, noneSelector(),
                "RULE_BATCH_A", 1, RuleStatus.DRAFT, RuleMetric.COUNT, now);
        LimitRule ruleB = rule(Set.of("ECOM", "OCT", "AFT"), OperationDirection.IN, noneSelector(),
                "RULE_BATCH_B", 1, RuleStatus.DRAFT, RuleMetric.COUNT, now);

        repository.saveRule(ruleA);
        repository.saveRule(ruleB);

        Map<UUID, LimitRule> listedById = repository.listRules().stream()
                .collect(Collectors.toMap(LimitRule::id, r -> r));

        LimitRule listedA = listedById.get(ruleA.id());
        LimitRule listedB = listedById.get(ruleB.id());
        assertThat(listedA).isNotNull();
        assertThat(listedB).isNotNull();
        assertThat(listedA.operationTypes()).containsExactlyInAnyOrder("SBP_C2B", "SBP_B2C");
        assertThat(listedB.operationTypes()).containsExactlyInAnyOrder("ECOM", "OCT", "AFT");
        assertThat(listedA.operationTypes())
                .isEqualTo(repository.findRule(ruleA.id()).orElseThrow().operationTypes());
        assertThat(listedB.operationTypes())
                .isEqualTo(repository.findRule(ruleB.id()).orElseThrow().operationTypes());
    }

    @Test
    void mapsUnknownOperationTypeReferenceToProblemCode() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");

        assertThatThrownBy(() -> repository.saveRule(rule(Set.of("UNKNOWN_TYPE"), OperationDirection.IN, noneSelector(),
                "RULE_UNKNOWN_TYPE", 1, RuleStatus.DRAFT, RuleMetric.AMOUNT, now)))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("INVALID_RULE_DEFINITION");
    }

    private LimitRule rule(
            Set<String> operationTypes,
            OperationDirection direction,
            RuleSelector<AttributeSelectorType> attributeSelector,
            String code,
            int version,
            RuleStatus status,
            RuleMetric metric,
            Instant now
    ) {
        Instant activatedAt = status == RuleStatus.DRAFT ? null : now;
        Instant disabledAt = status == RuleStatus.DISABLED ? now.plusSeconds(60) : null;
        String currency = metric == RuleMetric.AMOUNT ? "RUB" : null;
        return new LimitRule(
                UUID.randomUUID(),
                code,
                version,
                code,
                operationTypes,
                direction,
                new Measure(metric, RulePeriod.DAY, AggregationScope.OWNER, currency, null),
                // OWNER scope must not carry a limitTargetType (validation 4 / V13 DB check).
                null,
                new BigDecimal("1000.00"),
                "template",
                attributeSelector,
                status,
                now,
                now,
                activatedAt,
                disabledAt
        );
    }

    private static RuleSelector<AttributeSelectorType> noneSelector() {
        return new RuleSelector<>(AttributeSelectorType.NONE, null);
    }
}
