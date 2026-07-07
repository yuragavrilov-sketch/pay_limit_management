package ru.copperside.paylimits.management.limitrule.domain;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

public record LimitKind(
        RuleMetric metric,
        RulePeriod period,
        LimitTargetType limitTargetType,
        OperationDirection direction,
        Set<String> operationTypes
) {
    public LimitKind {
        operationTypes = operationTypes == null ? Set.of() : Set.copyOf(operationTypes);
    }

    public static LimitKind of(LimitRule rule) {
        return new LimitKind(
                rule.measure().metric(),
                rule.measure().period(),
                rule.limitTargetType(),
                rule.direction(),
                rule.operationTypes());
    }

    public boolean conflictsWith(LimitKind other) {
        return metric == other.metric
                && Objects.equals(period, other.period)
                && Objects.equals(limitTargetType, other.limitTargetType)
                && direction == other.direction
                && !Collections.disjoint(operationTypes, other.operationTypes);
    }
}
