package ru.copperside.paylimits.management.limitrule.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleValidationTest {

    private LimitRuleRepository repository;
    private LimitRuleService service;

    @BeforeEach
    void setUp() {
        repository = mock(LimitRuleRepository.class);
        service = new LimitRuleService(
                repository,
                ru.copperside.paylimits.management.common.invariant.InvariantTestSupport.noOpChecker(),
                new ru.copperside.paylimits.management.common.invariant.InvariantTestSupport.PassThroughTransactionRunner(),
                Clock.systemUTC());
        when(repository.nextVersion(anyString())).thenReturn(1);
        when(repository.findDraftByCode(anyString())).thenReturn(Optional.empty());
        stubOperationType("OCT", OperationDirection.OUT, CounterpartyType.CARD);
        stubOperationType("SBP_B2C", OperationDirection.OUT, CounterpartyType.PHONE);
    }

    private void stubOperationType(String code, OperationDirection dir, CounterpartyType cp) {
        when(repository.findOperationTypeByCode(code)).thenReturn(Optional.of(new OperationType(
                UUID.randomUUID(), code, code, "FAM", dir, cp, true, 0, Instant.EPOCH, Instant.EPOCH)));
    }

    // MGT-U-01: PER_OPERATION with metric=COUNT -> 400
    @Test
    void rejectsPerOperationWithCountMetric() {
        var cmd = create(Set.of("OCT"), OperationDirection.OUT,
                new Measure(RuleMetric.COUNT, RulePeriod.PER_OPERATION, null, null, null),
                null, new BigDecimal("10"));
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class)
                .extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    // MGT-U-02: INTERVAL without intervalMinutes OR with aggregationScope=OWNER -> 400
    @Test
    void rejectsIntervalWithoutMinutes() {
        var cmd = create(Set.of("SBP_B2C"), OperationDirection.OUT,
                new Measure(RuleMetric.INTERVAL, null, AggregationScope.TARGET, null, null),
                LimitTargetType.PHONE, null);
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class);
    }

    @Test
    void rejectsIntervalWithOwnerScope() {
        var cmd = create(Set.of("SBP_B2C"), OperationDirection.OUT,
                new Measure(RuleMetric.INTERVAL, null, AggregationScope.OWNER, null, 5),
                null, null);
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class);
    }

    // MGT-U-03: operationTypes not matching direction -> 400
    @Test
    void rejectsOperationTypesNotMatchingDirection() {
        var cmd = create(Set.of("OCT"), OperationDirection.IN,
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                null, new BigDecimal("100"));
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class);
    }

    // MGT-U-04: TARGET rule mixing counterparties (OCT card + SBP_B2C phone) -> 400
    @Test
    void rejectsTargetRuleMixingCounterparties() {
        var cmd = create(Set.of("OCT", "SBP_B2C"), OperationDirection.OUT,
                new Measure(RuleMetric.COUNT, RulePeriod.DAY, AggregationScope.TARGET, null, null),
                LimitTargetType.CARD, new BigDecimal("3"));
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class);
    }

    // MGT-U-05: errorMessageTemplate with a bad placeholder (not %d/%f/%s) -> 400
    @Test
    void rejectsInvalidTemplatePlaceholder() {
        var cmd = new CreateLimitRuleCommand("R", "name", Set.of("OCT"), OperationDirection.OUT,
                new Measure(RuleMetric.AMOUNT, RulePeriod.PER_OPERATION, null, "RUB", null),
                null, new BigDecimal("600000"),
                "Bad %x placeholder", new RuleSelector<>(AttributeSelectorType.NONE, null));
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class);
    }

    // Regression: a trailing '%' with no following character must be rejected, not silently
    // skipped by the placeholder scan.
    @Test
    void rejectsErrorTemplateWithTrailingPercent() {
        var cmd = new CreateLimitRuleCommand("R", "name", Set.of("OCT"), OperationDirection.OUT,
                new Measure(RuleMetric.AMOUNT, RulePeriod.PER_OPERATION, null, "RUB", null),
                null, new BigDecimal("600000"),
                "trailing%", new RuleSelector<>(AttributeSelectorType.NONE, null));
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class)
                .extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    // Regression: a bare/dangling '%' at the end of the template (after a space) must be rejected.
    @Test
    void rejectsErrorTemplateWithDanglingPercent() {
        var cmd = new CreateLimitRuleCommand("R", "name", Set.of("OCT"), OperationDirection.OUT,
                new Measure(RuleMetric.AMOUNT, RulePeriod.PER_OPERATION, null, "RUB", null),
                null, new BigDecimal("600000"),
                "Bad %", new RuleSelector<>(AttributeSelectorType.NONE, null));
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class)
                .extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    // Regression: %% (escaped literal percent) combined with all supported placeholders is accepted.
    @Test
    void acceptsErrorTemplateWithEscapedPercentAndAllPlaceholders() {
        var cmd = new CreateLimitRuleCommand("R", "name", Set.of("OCT"), OperationDirection.OUT,
                new Measure(RuleMetric.AMOUNT, RulePeriod.PER_OPERATION, null, "RUB", null),
                null, new BigDecimal("600000"),
                "%% escaped, limit %d used %f left %s", new RuleSelector<>(AttributeSelectorType.NONE, null));
        when(repository.saveRule(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.createRule(cmd).code()).isEqualTo("R");
    }

    @Test
    void acceptsValidPerOperationAmountRule() {
        var cmd = new CreateLimitRuleCommand("R", "name", Set.of("OCT", "SBP_B2C"), OperationDirection.OUT,
                new Measure(RuleMetric.AMOUNT, RulePeriod.PER_OPERATION, null, "RUB", null),
                null, new BigDecimal("600000"),
                "Лимит %d использовано %f сумма %s", new RuleSelector<>(AttributeSelectorType.NONE, null));
        when(repository.saveRule(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.createRule(cmd).code()).isEqualTo("R");
    }

    // Closes a coverage gap: INTERVAL rules were previously only exercised at the repository
    // layer, not through service-level validation. Confirms a valid INTERVAL rule passes.
    @Test
    void acceptsValidIntervalRule() {
        var cmd = new CreateLimitRuleCommand("R", "name", Set.of("SBP_B2C"), OperationDirection.OUT,
                new Measure(RuleMetric.INTERVAL, null, AggregationScope.TARGET, null, 15),
                LimitTargetType.PHONE, null,
                "Лимит %d использовано %f значение %s", new RuleSelector<>(AttributeSelectorType.NONE, null));
        when(repository.saveRule(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.createRule(cmd).code()).isEqualTo("R");
    }

    // Closes a Task 2 review finding: activation must re-check operationTypes still resolve/are enabled,
    // not defer that to manifest compilation.
    @Test
    void activateRuleRejectsWhenOperationTypeSinceDisabled() {
        var cmd = create(Set.of("OCT"), OperationDirection.OUT,
                new Measure(RuleMetric.AMOUNT, RulePeriod.PER_OPERATION, null, "RUB", null),
                null, new BigDecimal("100"));
        when(repository.saveRule(any())).thenAnswer(inv -> inv.getArgument(0));
        LimitRule draft = service.createRule(cmd);
        when(repository.findRule(draft.id())).thenReturn(Optional.of(draft));
        when(repository.findActiveByCode(draft.code())).thenReturn(Optional.empty());
        // Operation type was disabled after the draft was created.
        when(repository.findOperationTypeByCode("OCT")).thenReturn(Optional.of(new OperationType(
                UUID.randomUUID(), "OCT", "OCT", "FAM", OperationDirection.OUT, CounterpartyType.CARD,
                false, 0, Instant.EPOCH, Instant.EPOCH)));

        assertThatThrownBy(() -> service.activateRule(draft.id()))
                .isInstanceOf(LimitRuleProblemException.class)
                .extracting("code").isEqualTo("OPERATION_TYPE_DISABLED");
    }

    // Validation 4 (extended): OWNER-scope rules must not carry a limitTargetType.
    @Test
    void rejectsOwnerScopeAmountRuleWithLimitTargetType() {
        var cmd = create(Set.of("OCT"), OperationDirection.OUT,
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                LimitTargetType.CARD, new BigDecimal("100"));
        assertThatThrownBy(() -> service.createRule(cmd))
                .isInstanceOf(LimitRuleProblemException.class)
                .extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    // Companion to the rejection above: the same OWNER-scope AMOUNT rule with limitTargetType=null
    // must still be accepted.
    @Test
    void acceptsValidOwnerScopeAmountRule() {
        var cmd = create(Set.of("OCT"), OperationDirection.OUT,
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                null, new BigDecimal("100"));
        when(repository.saveRule(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.createRule(cmd).code()).isEqualTo("R");
    }

    private CreateLimitRuleCommand create(Set<String> ops, OperationDirection dir, Measure m,
                                          LimitTargetType target, BigDecimal value) {
        return new CreateLimitRuleCommand("R", "name", ops, dir, m, target, value,
                "Лимит %d использовано %f значение %s", new RuleSelector<>(AttributeSelectorType.NONE, null));
    }
}
