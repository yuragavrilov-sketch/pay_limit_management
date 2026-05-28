package ru.copperside.paylimits.management.limitrule.domain;

import java.time.Instant;
import java.util.UUID;

public record LimitRule(
        UUID id,
        String code,
        int version,
        String name,
        RuleSelector<OperationSelectorType> operationSelector,
        OperationDirection direction,
        RuleSelector<AttributeSelectorType> attributeSelector,
        LimitTargetType targetType,
        RuleMetric metric,
        RulePeriod period,
        String currency,
        RuleStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant activatedAt,
        Instant disabledAt
) {
    public boolean active() {
        return status == RuleStatus.ACTIVE;
    }
}
