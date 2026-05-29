package ru.copperside.paylimits.management.limitassignment.domain;

import java.time.Instant;
import java.util.UUID;

public record LimitAssignment(
        UUID id,
        UUID ruleId,
        AssignmentOwnerType ownerType,
        String ownerId,
        LimitMode limitMode,
        String limitValue,
        Instant validFrom,
        Instant validTo,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
