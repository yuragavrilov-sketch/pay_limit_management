package ru.copperside.paylimits.management.effectivelimits.domain;

import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;

import java.math.BigDecimal;

/**
 * A less-specific-level candidate overridden by the applied level for a resolved limit kind
 * (spec §3.5 {@code overrides}) — kept for operator transparency ("why this value applies").
 */
public record LimitOverride(
        AssignmentOwnerType level,
        String ownerId,
        BigDecimal limitValue
) {
}
