package ru.copperside.paylimits.management.limitrule.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitRuleServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-27T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private FakeRepository repository;
    private LimitRuleService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        service = new LimitRuleService(repository, CLOCK);
    }

    @Test
    void listsRuleDictionaries() {
        repository.addOperationType("SBP_C2B", OperationDirection.IN, true);

        RuleDictionaries dictionaries = service.getRuleDictionaries();

        assertThat(dictionaries.operationFamilies()).extracting(DictionaryItem::code).contains("SBP");
        assertThat(dictionaries.operationTypes()).extracting(OperationType::code).contains("SBP_C2B");
        assertThat(dictionaries.attributeSelectorTypes()).contains(AttributeSelectorType.PAYMENT_SYSTEM);
        assertThat(dictionaries.targetTypes())
                .contains(LimitTargetType.CARD, LimitTargetType.PHONE, LimitTargetType.ACCOUNT);
    }

    @Test
    void createsOperationTypeWithGeneratedIdAndAuditTimestamps() {
        OperationType type = service.createOperationType(new CreateOperationTypeCommand(
                "SBP_C2B", "SBP C2B", "SBP", OperationDirection.IN, CounterpartyType.PHONE
        ));

        assertThat(type.id()).isNotNull();
        assertThat(type.code()).isEqualTo("SBP_C2B");
        assertThat(type.enabled()).isTrue();
        assertThat(type.sortOrder()).isZero();
        assertThat(type.createdAt()).isEqualTo(NOW);
        assertThat(repository.operationTypes).containsExactly(type);
    }

    @Test
    void rejectsOperationTypeCreationWithoutRequiredFields() {
        assertThatThrownBy(() -> service.createOperationType(new CreateOperationTypeCommand(
                " ", "SBP C2B", "SBP", OperationDirection.IN, CounterpartyType.PHONE
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");

        assertThatThrownBy(() -> service.createOperationType(new CreateOperationTypeCommand(
                "SBP_C2B", null, "SBP", OperationDirection.IN, CounterpartyType.PHONE
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");

        assertThatThrownBy(() -> service.createOperationType(new CreateOperationTypeCommand(
                "SBP_C2B", "SBP C2B", "", OperationDirection.IN, CounterpartyType.PHONE
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");

        assertThatThrownBy(() -> service.createOperationType(new CreateOperationTypeCommand(
                "SBP_C2B", "SBP C2B", "SBP", null, CounterpartyType.PHONE
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");

        assertThatThrownBy(() -> service.createOperationType(new CreateOperationTypeCommand(
                "SBP_C2B", "SBP C2B", "SBP", OperationDirection.IN, null
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");
    }

    @Test
    void patchesOperationTypeAndPreservesNullFields() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);

        OperationType patched = service.patchOperationType(type.id(), new PatchOperationTypeCommand(
                "SBP C2B updated", null, OperationDirection.OUT, null, null
        ));

        assertThat(patched.code()).isEqualTo(type.code());
        assertThat(patched.name()).isEqualTo("SBP C2B updated");
        assertThat(patched.familyCode()).isEqualTo(type.familyCode());
        assertThat(patched.direction()).isEqualTo(OperationDirection.OUT);
        assertThat(patched.counterpartyType()).isEqualTo(type.counterpartyType());
        assertThat(patched.enabled()).isEqualTo(type.enabled());
        assertThat(patched.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsDisablingOperationTypeWhenActiveRulesUseIt() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule active = repository.addRule(
                Set.of(type.code()), OperationDirection.IN, noneSelector(),
                "RULE_SBP_C2B_DAY", 1, RuleMetric.AMOUNT, RulePeriod.DAY, RuleStatus.ACTIVE
        );

        assertThat(active.active()).isTrue();
        assertThatThrownBy(() -> service.patchOperationType(type.id(), new PatchOperationTypeCommand(
                null, null, null, null, false
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("OPERATION_TYPE_IN_USE");
    }

    @Test
    void createsDraftAmountRule() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);

        LimitRule rule = service.createRule(amountRule(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", Set.of(type.code()), OperationDirection.IN
        ));

        assertThat(rule.version()).isEqualTo(1);
        assertThat(rule.status()).isEqualTo(RuleStatus.DRAFT);
        assertThat(rule.operationTypes()).containsExactly("SBP_C2B");
        assertThat(rule.direction()).isEqualTo(OperationDirection.IN);
        assertThat(rule.attributeSelector()).isEqualTo(noneSelector());
        // OWNER-scope rules must not carry a limitTargetType (validation 4).
        assertThat(rule.limitTargetType()).isNull();
        assertThat(rule.measure().metric()).isEqualTo(RuleMetric.AMOUNT);
        assertThat(rule.limitValue()).isEqualByComparingTo("1000.00");
        assertThat(rule.errorMessageTemplate()).isEqualTo("template");
    }

    @Test
    void createsDraftRuleWithMultipleOperationTypesAndAttributeSelector() {
        repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        repository.addOperationType("SBP_B2B_IN", OperationDirection.IN, true);

        LimitRule rule = service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_MIR_DAY",
                "SBP MIR daily amount",
                Set.of("SBP_C2B", "SBP_B2B_IN"),
                OperationDirection.IN,
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                null,
                new BigDecimal("1000.00"),
                "template",
                new RuleSelector<>(AttributeSelectorType.PAYMENT_SYSTEM, "MIR")
        ));

        assertThat(rule.operationTypes()).containsExactlyInAnyOrder("SBP_C2B", "SBP_B2B_IN");
        assertThat(rule.attributeSelector()).isEqualTo(new RuleSelector<>(AttributeSelectorType.PAYMENT_SYSTEM, "MIR"));
    }

    @Test
    void rejectsInvalidOperationTypeAndAttributeDefinitions() {
        OperationType disabled = repository.addOperationType("SBP_DISABLED", OperationDirection.IN, false);

        assertThatThrownBy(() -> service.createRule(amountRule(
                "RULE_UNKNOWN_TYPE", "Unknown type", Set.of("UNKNOWN"), OperationDirection.IN
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_SELECTOR_INVALID");

        assertThatThrownBy(() -> service.createRule(amountRule(
                "RULE_DISABLED_TYPE", "Disabled type", Set.of(disabled.code()), OperationDirection.IN
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("OPERATION_TYPE_DISABLED");

        repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        assertThatThrownBy(() -> service.createRule(new CreateLimitRuleCommand(
                "RULE_UNKNOWN_PAYMENT_SYSTEM",
                "Unknown payment system",
                Set.of("SBP_C2B"),
                OperationDirection.IN,
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                null,
                new BigDecimal("1000.00"),
                "template",
                new RuleSelector<>(AttributeSelectorType.PAYMENT_SYSTEM, "UNKNOWN")
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_SELECTOR_INVALID");
    }

    @Test
    void rejectsRuleCreationWithoutOperationTypes() {
        assertThatThrownBy(() -> service.createRule(amountRule(
                "RULE_NO_TYPES", "No operation types", Set.of(), OperationDirection.IN
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");
    }

    @Test
    void createRuleRejectsWhenDraftAlreadyExistsForCode() {
        repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        service.createRule(amountRule(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", Set.of("SBP_C2B"), OperationDirection.IN
        ));

        assertThatThrownBy(() -> service.createRule(amountRule(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount duplicate", Set.of("SBP_C2B"), OperationDirection.IN
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_DRAFT_EXISTS");
    }

    @Test
    void patchesDraftRuleOperationTypesAndMeasure() {
        repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        repository.addOperationType("SBP_B2C", OperationDirection.OUT, true);
        LimitRule draft = service.createRule(amountRule(
                "RULE_SBP_DAY", "SBP daily amount", Set.of("SBP_C2B"), OperationDirection.IN
        ));

        LimitRule patched = service.patchRule(draft.id(), new PatchLimitRuleCommand(
                "Updated monthly count",
                Set.of("SBP_B2C"),
                OperationDirection.OUT,
                new Measure(RuleMetric.COUNT, RulePeriod.MONTH, AggregationScope.OWNER, null, null),
                null,
                new BigDecimal("5"),
                "template",
                noneSelector()
        ));

        assertThat(patched.name()).isEqualTo("Updated monthly count");
        assertThat(patched.operationTypes()).containsExactly("SBP_B2C");
        assertThat(patched.direction()).isEqualTo(OperationDirection.OUT);
        assertThat(patched.measure().metric()).isEqualTo(RuleMetric.COUNT);
        assertThat(patched.measure().period()).isEqualTo(RulePeriod.MONTH);
    }

    @Test
    void activatesDraftAndMakesItImmutable() {
        repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule draft = service.createRule(amountRule(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", Set.of("SBP_C2B"), OperationDirection.IN
        ));

        LimitRule active = service.activateRule(draft.id());

        assertThat(active.status()).isEqualTo(RuleStatus.ACTIVE);
        assertThat(active.activatedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.patchRule(active.id(), new PatchLimitRuleCommand(
                "Changed", null, null, null, null, null, null, null
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_STATUS_CONFLICT");
    }

    @Test
    void activateRuleRejectsWhenAnotherActiveVersionExistsForCode() {
        repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule active = service.activateRule(service.createRule(amountRule(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", Set.of("SBP_C2B"), OperationDirection.IN
        )).id());
        LimitRule draft = service.createNewVersion(active.id());

        assertThatThrownBy(() -> service.activateRule(draft.id()))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_STATUS_CONFLICT");
    }

    @Test
    void createsNextDraftVersionFromActiveRule() {
        repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule active = service.activateRule(service.createRule(amountRule(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", Set.of("SBP_C2B"), OperationDirection.IN
        )).id());

        LimitRule next = service.createNewVersion(active.id());

        assertThat(next.code()).isEqualTo("RULE_SBP_C2B_DAY");
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.status()).isEqualTo(RuleStatus.DRAFT);
        assertThat(next.operationTypes()).isEqualTo(active.operationTypes());
        assertThat(next.direction()).isEqualTo(active.direction());
    }

    @Test
    void rejectsNewVersionWhenDraftAlreadyExistsForCode() {
        LimitRule active = repository.addRule(
                Set.of("SBP_C2B"), OperationDirection.IN, noneSelector(),
                "RULE_SBP_C2B_DAY", 1, RuleMetric.AMOUNT, RulePeriod.DAY, RuleStatus.ACTIVE
        );
        repository.addRule(
                Set.of("SBP_C2B"), OperationDirection.IN, noneSelector(),
                "RULE_SBP_C2B_DAY", 2, RuleMetric.AMOUNT, RulePeriod.DAY, RuleStatus.DRAFT
        );

        assertThatThrownBy(() -> service.createNewVersion(active.id()))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_DRAFT_EXISTS");
    }

    @Test
    void disablesOnlyActiveRuleAndSetsDisabledAt() {
        LimitRule active = repository.addRule(
                Set.of("SBP_C2B"), OperationDirection.IN, noneSelector(),
                "RULE_SBP_C2B_DAY", 1, RuleMetric.AMOUNT, RulePeriod.DAY, RuleStatus.ACTIVE
        );

        LimitRule disabled = service.disableRule(active.id());

        assertThat(disabled.status()).isEqualTo(RuleStatus.DISABLED);
        assertThat(disabled.disabledAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.disableRule(disabled.id()))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_STATUS_CONFLICT");
    }

    @Test
    void rejectsReactivationOfDisabledRule() {
        LimitRule disabled = repository.addRule(
                Set.of("SBP_C2B"), OperationDirection.IN, noneSelector(),
                "RULE_SBP_C2B_DAY", 1, RuleMetric.AMOUNT, RulePeriod.DAY, RuleStatus.DISABLED
        );

        assertThatThrownBy(() -> service.activateRule(disabled.id()))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_STATUS_CONFLICT");
    }

    private CreateLimitRuleCommand amountRule(
            String code,
            String name,
            Set<String> operationTypes,
            OperationDirection direction
    ) {
        return new CreateLimitRuleCommand(
                code,
                name,
                operationTypes,
                direction,
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                null,
                new BigDecimal("1000.00"),
                "template",
                noneSelector()
        );
    }

    private static RuleSelector<AttributeSelectorType> noneSelector() {
        return new RuleSelector<>(AttributeSelectorType.NONE, null);
    }

    static class FakeRepository implements LimitRuleRepository {
        final List<OperationType> operationTypes = new ArrayList<>();
        final List<LimitRule> rules = new ArrayList<>();
        final Set<String> operationFamilies = Set.of("SBP", "CARD");
        final Set<String> paymentSystems = Set.of("MIR", "VISA");
        final Set<String> issuerCountries = Set.of("RU");
        final Set<String> issuerBanks = Set.of("TKB");
        final Set<String> bins = Set.of("220220");
        final Set<String> cardTypes = Set.of("DEBIT", "CREDIT");
        final Set<String> cardLevels = Set.of("STANDARD", "GOLD");

        OperationType addOperationType(String code, OperationDirection direction, boolean enabled) {
            OperationType type = new OperationType(
                    UUID.randomUUID(), code, code, "SBP", direction, CounterpartyType.PHONE, enabled, 10,
                    Instant.EPOCH, Instant.EPOCH
            );
            operationTypes.add(type);
            return type;
        }

        LimitRule addRule(
                Set<String> operationTypes,
                OperationDirection direction,
                RuleSelector<AttributeSelectorType> attributeSelector,
                String code,
                int version,
                RuleMetric metric,
                RulePeriod period,
                RuleStatus status
        ) {
            LimitRule rule = new LimitRule(
                    UUID.randomUUID(),
                    code,
                    version,
                    code,
                    operationTypes,
                    direction,
                    new Measure(metric, period, AggregationScope.OWNER, metric == RuleMetric.AMOUNT ? "RUB" : null, null),
                    LimitTargetType.PHONE,
                    metric == RuleMetric.INTERVAL ? null : new BigDecimal("1000.00"),
                    "template",
                    attributeSelector,
                    status,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    status == RuleStatus.ACTIVE ? Instant.EPOCH : null,
                    status == RuleStatus.DISABLED ? Instant.EPOCH : null
            );
            rules.add(rule);
            return rule;
        }

        @Override
        public List<OperationType> listOperationTypes() {
            return List.copyOf(operationTypes);
        }

        @Override
        public RuleDictionaries getRuleDictionaries() {
            return new RuleDictionaries(
                    dictionaryItems(operationFamilies),
                    listOperationTypes(),
                    dictionaryItems(paymentSystems),
                    dictionaryItems(issuerCountries),
                    dictionaryItems(issuerBanks),
                    dictionaryItems(bins),
                    dictionaryItems(cardTypes),
                    dictionaryItems(cardLevels),
                    Arrays.asList(OperationDirection.values()),
                    Arrays.asList(AttributeSelectorType.values()),
                    Arrays.asList(LimitTargetType.values()),
                    Arrays.asList(RuleMetric.values()),
                    Arrays.asList(RulePeriod.values()),
                    Arrays.asList(CounterpartyType.values()),
                    Arrays.asList(AggregationScope.values())
            );
        }

        @Override
        public Optional<OperationType> findOperationType(UUID id) {
            return operationTypes.stream().filter(type -> type.id().equals(id)).findFirst();
        }

        @Override
        public Optional<OperationType> findOperationTypeByCode(String code) {
            return operationTypes.stream().filter(type -> type.code().equals(code)).findFirst();
        }

        @Override
        public boolean attributeValueExists(AttributeSelectorType type, String code) {
            return switch (type) {
                case NONE -> code == null;
                case PAYMENT_SYSTEM -> paymentSystems.contains(code);
                case ISSUER_COUNTRY -> issuerCountries.contains(code);
                case BANK -> issuerBanks.contains(code);
                case BIN -> bins.contains(code);
                case CARD_TYPE -> cardTypes.contains(code);
                case CARD_LEVEL -> cardLevels.contains(code);
            };
        }

        @Override
        public OperationType saveOperationType(OperationType type) {
            operationTypes.add(type);
            return type;
        }

        @Override
        public OperationType updateOperationType(OperationType type) {
            operationTypes.replaceAll(existing -> existing.id().equals(type.id()) ? type : existing);
            return type;
        }

        @Override
        public boolean hasActiveRulesForOperationTypeCode(String operationTypeCode) {
            return rules.stream()
                    .filter(rule -> rule.status() == RuleStatus.ACTIVE)
                    .anyMatch(rule -> rule.operationTypes().contains(operationTypeCode));
        }

        @Override
        public List<LimitRule> listRules() {
            return List.copyOf(rules);
        }

        @Override
        public Optional<LimitRule> findRule(UUID id) {
            return rules.stream().filter(rule -> rule.id().equals(id)).findFirst();
        }

        @Override
        public Optional<LimitRule> findDraftByCode(String code) {
            return rules.stream()
                    .filter(rule -> rule.code().equals(code))
                    .filter(rule -> rule.status() == RuleStatus.DRAFT)
                    .findFirst();
        }

        @Override
        public Optional<LimitRule> findActiveByCode(String code) {
            return rules.stream()
                    .filter(rule -> rule.code().equals(code))
                    .filter(rule -> rule.status() == RuleStatus.ACTIVE)
                    .findFirst();
        }

        @Override
        public int nextVersion(String code) {
            return rules.stream()
                    .filter(rule -> rule.code().equals(code))
                    .max(Comparator.comparingInt(LimitRule::version))
                    .map(rule -> rule.version() + 1)
                    .orElse(1);
        }

        @Override
        public LimitRule saveRule(LimitRule rule) {
            rules.add(rule);
            return rule;
        }

        @Override
        public LimitRule updateRule(LimitRule rule) {
            rules.replaceAll(existing -> existing.id().equals(rule.id()) ? rule : existing);
            return rule;
        }

        private List<DictionaryItem> dictionaryItems(Set<String> codes) {
            return codes.stream()
                    .sorted()
                    .map(code -> new DictionaryItem(code, code, true, 10, Instant.EPOCH, Instant.EPOCH))
                    .toList();
        }
    }
}
