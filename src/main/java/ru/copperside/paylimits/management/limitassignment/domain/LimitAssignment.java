package ru.copperside.paylimits.management.limitassignment.domain;

import java.time.Instant;
import java.util.UUID;

public record LimitAssignment(
        UUID id,
        UUID ruleId,
        AssignmentOwnerType ownerType,
        String ownerId,
        LimitMode limitMode,
        Instant validFrom,
        Instant validTo,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * PATCH transition: replaces the mutable scheduling/mode fields (limitMode, validFrom, validTo,
     * enabled) and bumps updatedAt; identity fields (id, ruleId, ownerType, ownerId) and createdAt are
     * carried over unchanged. Avoids hand-repeating the full 10-arg positional constructor (and the
     * same-typed-field transposition hazard that comes with it) at the call site.
     */
    public LimitAssignment patched(LimitMode limitMode, Instant validFrom, Instant validTo, boolean enabled, Instant updatedAt) {
        return new LimitAssignment(id, ruleId, ownerType, ownerId, limitMode, validFrom, validTo, enabled, createdAt, updatedAt);
    }

    /** DISABLE transition: enabled -> false, updatedAt -> {@code updatedAt}; all other fields unchanged. */
    public LimitAssignment disabled(Instant updatedAt) {
        return new LimitAssignment(id, ruleId, ownerType, ownerId, limitMode, validFrom, validTo, false, createdAt, updatedAt);
    }
}
