package ru.copperside.paylimits.management.limitrule.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CompiledRule(
        UUID ruleId,
        String code,
        int version,
        Matcher matcher,
        Measure measure,
        BigDecimal limitValue,
        String errorMessageTemplate
) {
    public record Matcher(
            List<String> operationTypes,
            OperationDirection direction,
            RuleSelector<AttributeSelectorType> attribute,
            LimitTargetType targetType
    ) {
        public Matcher {
            operationTypes = operationTypes == null ? List.of() : List.copyOf(operationTypes);
        }
    }
}
