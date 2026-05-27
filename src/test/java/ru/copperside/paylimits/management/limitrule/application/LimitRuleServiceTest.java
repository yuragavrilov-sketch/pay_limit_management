package ru.copperside.paylimits.management.limitrule.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
    void createsOperationTypeWithGeneratedIdAndAuditTimestamps() {
        OperationType type = service.createOperationType(new CreateOperationTypeCommand(
                "SBP_C2C", "SBP C2C", "SBP", OperationDirection.ALL
        ));

        assertThat(type.id()).isNotNull();
        assertThat(type.code()).isEqualTo("SBP_C2C");
        assertThat(type.enabled()).isTrue();
        assertThat(type.createdAt()).isEqualTo(NOW);
        assertThat(repository.operationTypes).containsExactly(type);
    }

    @Test
    void rejectsOperationTypeCreationWithoutRequiredFields() {
        assertThatThrownBy(() -> service.createOperationType(new CreateOperationTypeCommand(
                " ", "SBP C2C", "SBP", OperationDirection.ALL
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");

        assertThatThrownBy(() -> service.createOperationType(new CreateOperationTypeCommand(
                "SBP_C2C", null, "SBP", OperationDirection.ALL
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");

        assertThatThrownBy(() -> service.createOperationType(new CreateOperationTypeCommand(
                "SBP_C2C", "SBP C2C", "", OperationDirection.ALL
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");

        assertThatThrownBy(() -> service.createOperationType(new CreateOperationTypeCommand(
                "SBP_C2C", "SBP C2C", "SBP", null
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");
    }

    @Test
    void patchesOperationTypeAndPreservesNullFields() {
        OperationType type = repository.addOperationType("SBP_C2C", OperationDirection.ALL, true);

        OperationType patched = service.patchOperationType(type.id(), new PatchOperationTypeCommand(
                "SBP C2C updated", null, null, null
        ));

        assertThat(patched.code()).isEqualTo(type.code());
        assertThat(patched.name()).isEqualTo("SBP C2C updated");
        assertThat(patched.familyCode()).isEqualTo(type.familyCode());
        assertThat(patched.direction()).isEqualTo(type.direction());
        assertThat(patched.enabled()).isEqualTo(type.enabled());
        assertThat(patched.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsOperationTypeDirectionChangeWhenActiveRulesUseIt() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule active = repository.addRule(type, "RULE_SBP_C2B_DAY", 1, RuleMetric.AMOUNT, RulePeriod.DAY, RuleStatus.ACTIVE);

        assertThat(active.active()).isTrue();
        assertThatThrownBy(() -> service.patchOperationType(type.id(), new PatchOperationTypeCommand(
                null, null, OperationDirection.OUT, null
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("OPERATION_TYPE_IN_USE");
    }

    @Test
    void createsDraftAmountRuleWithRubCurrencyAndVersionOne() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);

        LimitRule rule = service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", type.id(), RuleMetric.AMOUNT, RulePeriod.DAY
        ));

        assertThat(rule.version()).isEqualTo(1);
        assertThat(rule.status()).isEqualTo(RuleStatus.DRAFT);
        assertThat(rule.targetType()).isEqualTo("PHONE");
        assertThat(rule.currency()).isEqualTo("RUB");
    }

    @Test
    void createsDraftCountRuleWithoutCurrency() {
        OperationType type = repository.addOperationType("SBP_B2C", OperationDirection.OUT, true);

        LimitRule rule = service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_B2C_COUNT_WEEK", "SBP B2C weekly count", type.id(), RuleMetric.COUNT, RulePeriod.WEEK
        ));

        assertThat(rule.currency()).isNull();
    }

    @Test
    void createRuleRejectsWhenDraftAlreadyExistsForCode() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", type.id(), RuleMetric.AMOUNT, RulePeriod.DAY
        ));

        assertThatThrownBy(() -> service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount duplicate", type.id(), RuleMetric.AMOUNT, RulePeriod.DAY
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_DRAFT_EXISTS");
    }

    @Test
    void rejectsRuleCreationForDisabledOperationType() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, false);

        assertThatThrownBy(() -> service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", type.id(), RuleMetric.AMOUNT, RulePeriod.DAY
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("OPERATION_TYPE_DISABLED");
    }

    @Test
    void activateRuleRejectsWhenAnotherActiveVersionExistsForCode() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule active = service.activateRule(service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", type.id(), RuleMetric.AMOUNT, RulePeriod.DAY
        )).id());
        LimitRule draft = service.createNewVersion(active.id());

        assertThatThrownBy(() -> service.activateRule(draft.id()))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_STATUS_CONFLICT");
    }

    @Test
    void activateRuleUsesCurrentOperationTypeDirection() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule draft = service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", type.id(), RuleMetric.AMOUNT, RulePeriod.DAY
        ));
        service.patchOperationType(type.id(), new PatchOperationTypeCommand(null, null, OperationDirection.OUT, null));

        LimitRule active = service.activateRule(draft.id());

        assertThat(active.operationTypeCode()).isEqualTo("SBP_C2B");
        assertThat(active.direction()).isEqualTo(OperationDirection.OUT);
    }

    @Test
    void activatesDraftAndMakesItImmutable() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule draft = service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", type.id(), RuleMetric.AMOUNT, RulePeriod.DAY
        ));

        LimitRule active = service.activateRule(draft.id());

        assertThat(active.status()).isEqualTo(RuleStatus.ACTIVE);
        assertThat(active.activatedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.patchRule(active.id(), new PatchLimitRuleCommand(
                "Changed", type.id(), RuleMetric.COUNT, RulePeriod.WEEK
        )))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_STATUS_CONFLICT");
    }

    @Test
    void rejectsActivationWhenOperationTypeWasDisabledAfterDraftCreation() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule draft = service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", type.id(), RuleMetric.AMOUNT, RulePeriod.DAY
        ));
        repository.updateOperationType(new OperationType(
                type.id(), type.code(), type.name(), type.familyCode(), type.direction(), false, type.createdAt(), type.updatedAt()
        ));

        assertThatThrownBy(() -> service.activateRule(draft.id()))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("OPERATION_TYPE_DISABLED");
    }

    @Test
    void createsNextDraftVersionFromActiveRule() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule active = service.activateRule(service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", type.id(), RuleMetric.AMOUNT, RulePeriod.DAY
        )).id());

        LimitRule next = service.createNewVersion(active.id());

        assertThat(next.code()).isEqualTo("RULE_SBP_C2B_DAY");
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.status()).isEqualTo(RuleStatus.DRAFT);
    }

    @Test
    void createsNextDraftVersionFromDisabledRule() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule active = service.activateRule(service.createRule(new CreateLimitRuleCommand(
                "RULE_SBP_C2B_DAY", "SBP C2B daily amount", type.id(), RuleMetric.AMOUNT, RulePeriod.DAY
        )).id());
        LimitRule disabled = service.disableRule(active.id());

        LimitRule next = service.createNewVersion(disabled.id());

        assertThat(next.code()).isEqualTo("RULE_SBP_C2B_DAY");
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.status()).isEqualTo(RuleStatus.DRAFT);
        assertThat(next.activatedAt()).isNull();
        assertThat(next.disabledAt()).isNull();
    }

    @Test
    void rejectsNewVersionWhenDraftAlreadyExistsForCode() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule active = repository.addRule(type, "RULE_SBP_C2B_DAY", 1, RuleMetric.AMOUNT, RulePeriod.DAY, RuleStatus.ACTIVE);
        repository.addRule(type, "RULE_SBP_C2B_DAY", 2, RuleMetric.AMOUNT, RulePeriod.DAY, RuleStatus.DRAFT);

        assertThatThrownBy(() -> service.createNewVersion(active.id()))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_DRAFT_EXISTS");
    }

    @Test
    void disablesOnlyActiveRuleAndSetsDisabledAt() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule active = repository.addRule(type, "RULE_SBP_C2B_DAY", 1, RuleMetric.AMOUNT, RulePeriod.DAY, RuleStatus.ACTIVE);

        LimitRule disabled = service.disableRule(active.id());

        assertThat(disabled.status()).isEqualTo(RuleStatus.DISABLED);
        assertThat(disabled.disabledAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.disableRule(disabled.id()))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_STATUS_CONFLICT");
    }

    @Test
    void rejectsReactivationOfDisabledRule() {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);
        LimitRule disabled = repository.addRule(type, "RULE_SBP_C2B_DAY", 1, RuleMetric.AMOUNT, RulePeriod.DAY, RuleStatus.DISABLED);

        assertThatThrownBy(() -> service.activateRule(disabled.id()))
                .isInstanceOf(LimitRuleProblemException.class)
                .hasMessageContaining("RULE_STATUS_CONFLICT");
    }

    static class FakeRepository implements LimitRuleRepository {
        final List<OperationType> operationTypes = new ArrayList<>();
        final List<LimitRule> rules = new ArrayList<>();

        OperationType addOperationType(String code, OperationDirection direction, boolean enabled) {
            OperationType type = new OperationType(UUID.randomUUID(), code, code, "SBP", direction, enabled, Instant.EPOCH, Instant.EPOCH);
            operationTypes.add(type);
            return type;
        }

        LimitRule addRule(
                OperationType type,
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
                    type.id(),
                    type.code(),
                    type.direction(),
                    "PHONE",
                    metric,
                    period,
                    metric == RuleMetric.AMOUNT ? "RUB" : null,
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
        public Optional<OperationType> findOperationType(UUID id) {
            return operationTypes.stream().filter(type -> type.id().equals(id)).findFirst();
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
        public boolean hasActiveRulesForOperationType(UUID operationTypeId) {
            return rules.stream()
                    .anyMatch(rule -> rule.operationTypeId().equals(operationTypeId) && rule.status() == RuleStatus.ACTIVE);
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
    }
}
