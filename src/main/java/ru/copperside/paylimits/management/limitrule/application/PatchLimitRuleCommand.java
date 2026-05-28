package ru.copperside.paylimits.management.limitrule.application;

import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;

public record PatchLimitRuleCommand(
        String name,
        RuleSelector<OperationSelectorType> operationSelector,
        OperationDirection direction,
        RuleSelector<AttributeSelectorType> attributeSelector,
        LimitTargetType targetType,
        RuleMetric metric,
        RulePeriod period,
        String currency
) {
}
