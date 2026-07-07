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
}
