package ru.copperside.paylimits.management.runtimeconfig.domain;

import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;

import java.util.List;
import java.util.UUID;

public record RuntimeCompiledRule(
        UUID ruleId,
        String code,
        int version,
        Matcher matcher,
        Measure measure
) {
    public record Matcher(
            RuleSelector<OperationSelectorType> operation,
            boolean operationMatchesAll,
            List<String> operationTypeCodes,
            OperationDirection direction,
            RuleSelector<AttributeSelectorType> attribute,
            LimitTargetType targetType
    ) {
        public Matcher {
            operationTypeCodes = operationTypeCodes == null ? List.of() : List.copyOf(operationTypeCodes);
        }
    }

    public record Measure(
            RuleMetric metric,
            RulePeriod period,
            String currency
    ) {
    }
}
