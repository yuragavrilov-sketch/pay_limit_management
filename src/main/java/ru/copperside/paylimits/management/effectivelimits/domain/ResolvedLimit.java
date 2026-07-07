package ru.copperside.paylimits.management.effectivelimits.domain;

import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One limit kind resolved for a merchant at a given instant (spec §3.5): the applied
 * (most-specific-level) assignment plus the less-specific candidates it overrides.
 */
public record ResolvedLimit(
        String ruleCode,
        int ruleVersion,
        String limitType,
        LimitTargetType targetType,
        OperationDirection direction,
        Set<String> operationTypes,
        AssignmentOwnerType appliedLevel,
        String ownerId,
        LimitMode mode,
        BigDecimal limitValue,
        UUID assignmentId,
        List<LimitOverride> overrides
) {
    public ResolvedLimit {
        operationTypes = operationTypes == null ? Set.of() : Set.copyOf(operationTypes);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }
}
