package ru.copperside.paylimits.management.limitrule.application;

import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;

import java.math.BigDecimal;
import java.util.Set;

public record PatchLimitRuleCommand(
        String name,
        Set<String> operationTypes,
        OperationDirection direction,
        Measure measure,
        LimitTargetType limitTargetType,
        BigDecimal limitValue,
        String errorMessageTemplate,
        RuleSelector<AttributeSelectorType> attributeSelector
) {
}
