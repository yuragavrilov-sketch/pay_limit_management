package ru.copperside.paylimits.management.limitrule.domain;

import java.util.UUID;

public record CompiledRule(
        UUID ruleId,
        String code,
        int version,
        Matcher matcher,
        Measure measure
) {
    public record Matcher(
            RuleSelector<OperationSelectorType> operation,
            OperationDirection direction,
            RuleSelector<AttributeSelectorType> attribute,
            LimitTargetType targetType
    ) {
    }

    public record Measure(
            RuleMetric metric,
            RulePeriod period,
            String currency
    ) {
    }
}
