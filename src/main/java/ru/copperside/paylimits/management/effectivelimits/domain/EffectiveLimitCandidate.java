package ru.copperside.paylimits.management.effectivelimits.domain;

import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitrule.domain.LimitKind;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * A single owner-level assignment of an ACTIVE rule in effect for a merchant at a given instant
 * (spec §3.5) — the raw input to level-priority resolution in {@code EffectiveLimitsService}.
 */
public record EffectiveLimitCandidate(
        UUID assignmentId,
        AssignmentOwnerType ownerLevel,
        String ownerId,
        LimitMode mode,
        UUID ruleId,
        String ruleCode,
        int ruleVersion,
        OperationDirection direction,
        RuleMetric metric,
        RulePeriod period,
        LimitTargetType limitTargetType,
        Set<String> operationTypes,
        BigDecimal limitValue
) {
    public EffectiveLimitCandidate {
        operationTypes = operationTypes == null ? Set.of() : Set.copyOf(operationTypes);
    }

    /**
     * The limit kind this candidate delivers (spec §2), used to group candidates from different
     * owner levels that resolve the same kind for a merchant.
     */
    public LimitKind kind() {
        return new LimitKind(metric, period, limitTargetType, direction, operationTypes);
    }
}
