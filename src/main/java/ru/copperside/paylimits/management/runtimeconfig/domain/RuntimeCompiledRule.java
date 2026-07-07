package ru.copperside.paylimits.management.runtimeconfig.domain;

import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RuntimeCompiledRule(
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
