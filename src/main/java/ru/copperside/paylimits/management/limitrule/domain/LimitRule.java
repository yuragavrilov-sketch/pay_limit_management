package ru.copperside.paylimits.management.limitrule.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record LimitRule(
        UUID id,
        String code,
        int version,
        String name,
        Set<String> operationTypes,
        OperationDirection direction,
        Measure measure,
        LimitTargetType limitTargetType,
        BigDecimal limitValue,
        String errorMessageTemplate,
        RuleSelector<AttributeSelectorType> attributeSelector,
        RuleStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant activatedAt,
        Instant disabledAt
) {
    public LimitRule {
        operationTypes = operationTypes == null ? Set.of() : Set.copyOf(operationTypes);
    }

    public boolean active() {
        return status == RuleStatus.ACTIVE;
    }
}
