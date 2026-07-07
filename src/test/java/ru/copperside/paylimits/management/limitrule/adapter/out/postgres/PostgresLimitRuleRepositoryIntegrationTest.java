package ru.copperside.paylimits.management.limitrule.adapter.out.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.DictionaryItem;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                typeSelector("SBP_C2B"),
                OperationDirection.IN,
                new RuleSelector<>(AttributeSelectorType.NONE, null),
                "RULE_SBP_C2B_DAY",
                1,
                RuleStatus.DRAFT,
                RuleMetric.AMOUNT,
                "RUB",
                Instant.parse("2026-05-27T09:00:00Z")
        );

        repository.saveRule(draft);
        LimitRule active = repository.updateRule(new LimitRule(
                draft.id(), draft.code(), draft.version(), draft.name(), draft.operationSelector(),
                draft.direction(), draft.attributeSelector(), draft.targetType(), draft.metric(), draft.period(),
                draft.currency(), RuleStatus.ACTIVE, draft.createdAt(), Instant.parse("2026-05-27T10:00:00Z"),
                Instant.parse("2026-05-27T10:00:00Z"), null
        ));

        assertThat(repository.findRule(draft.id())).contains(active);
        assertThat(repository.findActiveByCode(draft.code())).contains(active);
        assertThat(repository.hasActiveRulesForOperationTypeCode("SBP_C2B")).isTrue();
    }

    @Test
    void savesFamilyAndAttributeSelectorRule() {
        LimitRule draft = rule(
                new RuleSelector<>(OperationSelectorType.FAMILY, "CARD"),
                OperationDirection.IN,
                new RuleSelector<>(AttributeSelectorType.PAYMENT_SYSTEM, "MIR"),
                "RULE_CARD_MIR_DAY",
                1,
                RuleStatus.DRAFT,
                RuleMetric.AMOUNT,
                "RUB",
                Instant.parse("2026-05-27T09:00:00Z")
        );

        repository.saveRule(draft);

        assertThat(repository.findRule(draft.id())).contains(draft);
    }

    @Test
    void nextVersionUsesPersistedRuleVersions() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");

        repository.saveRule(rule(anyOperationSelector(), OperationDirection.OUT, noneSelector(),
                "RULE_SBP_B2C_COUNT_WEEK", 1, RuleStatus.DISABLED, RuleMetric.COUNT, null, now));
        repository.saveRule(rule(anyOperationSelector(), OperationDirection.OUT, noneSelector(),
                "RULE_SBP_B2C_COUNT_WEEK", 2, RuleStatus.DRAFT, RuleMetric.COUNT, null, now));

        assertThat(repository.nextVersion("RULE_SBP_B2C_COUNT_WEEK")).isEqualTo(3);
        assertThat(repository.nextVersion("RULE_NEW")).isEqualTo(1);
    }

    @Test
    void mapsDuplicateDraftRuleToProblemCode() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        repository.saveRule(rule(anyOperationSelector(), OperationDirection.ALL, noneSelector(),
                "RULE_SBP_COUNT_DAY", 1, RuleStatus.DRAFT, RuleMetric.COUNT, null, now));

        assertThatThrownBy(() -> repository.saveRule(rule(anyOperationSelector(), OperationDirection.ALL, noneSelector(),
                "RULE_SBP_COUNT_DAY", 2, RuleStatus.DRAFT, RuleMetric.COUNT, null, now)))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_DRAFT_EXISTS");
    }

    @Test
    void mapsInvalidRuleDefinitionToProblemCode() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");

        assertThatThrownBy(() -> repository.saveRule(rule(anyOperationSelector(), OperationDirection.ALL, noneSelector(),
                "RULE_SBP_COUNT_MONTH", 1, RuleStatus.DRAFT, RuleMetric.COUNT, "RUB", now)))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("INVALID_RULE_DEFINITION");
    }

    private LimitRule rule(
            RuleSelector<OperationSelectorType> operationSelector,
            OperationDirection direction,
            RuleSelector<AttributeSelectorType> attributeSelector,
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
                operationSelector,
                direction,
                attributeSelector,
                LimitTargetType.PHONE,
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

    private static RuleSelector<OperationSelectorType> anyOperationSelector() {
        return new RuleSelector<>(OperationSelectorType.ANY, null);
    }

    private static RuleSelector<OperationSelectorType> typeSelector(String value) {
        return new RuleSelector<>(OperationSelectorType.TYPE, value);
    }

    private static RuleSelector<AttributeSelectorType> noneSelector() {
        return new RuleSelector<>(AttributeSelectorType.NONE, null);
    }
}
