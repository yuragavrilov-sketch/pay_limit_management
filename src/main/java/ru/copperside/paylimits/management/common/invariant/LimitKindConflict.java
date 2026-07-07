package ru.copperside.paylimits.management.common.invariant;

import java.util.UUID;

/**
 * A single conflicting limit kind entry surfaced in the {@code conflicts} block of
 * limit-kind-invariant error responses (spec §3.4).
 */
public record LimitKindConflict(
        String merchantId,
        LimitKindView limitKind,
        UUID existingGroupId,
        UUID requestedGroupId
) {
}
