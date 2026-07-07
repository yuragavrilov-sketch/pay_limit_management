package ru.copperside.paylimits.management.limitrule.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LimitKindTest {

    @Test
    void conflictsWhenSameCheckTypeTargetDirectionAndOperationTypesIntersect() {
        LimitKind a = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT"));
        LimitKind b = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT", "AFT"));
        assertThat(a.conflictsWith(b)).isTrue();
    }

    @Test
    void doesNotConflictWhenOperationTypesDisjoint() {
        LimitKind a = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT"));
        LimitKind b = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("AFT"));
        assertThat(a.conflictsWith(b)).isFalse();
    }

    @Test
    void doesNotConflictWhenTargetTypeDiffers() {
        LimitKind a = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT"));
        LimitKind b = new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.OUT, Set.of("OCT"));
        assertThat(a.conflictsWith(b)).isFalse();
    }

    @Test
    void ofDerivesKindFromRule() {
        LimitRule rule = new LimitRule(
                UUID.randomUUID(),
                "R",
                1,
                "name",
                Set.of("OCT"),
                OperationDirection.OUT,
                new Measure(RuleMetric.COUNT, RulePeriod.DAY, AggregationScope.TARGET, null, null),
                LimitTargetType.CARD,
                new BigDecimal("3"),
                "template",
                new RuleSelector<>(AttributeSelectorType.NONE, null),
                RuleStatus.ACTIVE,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                null
        );

        LimitKind kind = LimitKind.of(rule);

        assertThat(kind.metric()).isEqualTo(RuleMetric.COUNT);
        assertThat(kind.period()).isEqualTo(RulePeriod.DAY);
        assertThat(kind.limitTargetType()).isEqualTo(LimitTargetType.CARD);
        assertThat(kind.direction()).isEqualTo(OperationDirection.OUT);
        assertThat(kind.operationTypes()).containsExactly("OCT");
    }
}
