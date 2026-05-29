package ru.copperside.paylimits.management.limitassignment.application;

import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;

import java.time.Instant;
import java.util.UUID;

public record CreateLimitAssignmentCommand(
        UUID ruleId,
        AssignmentOwnerType ownerType,
        String ownerId,
        LimitMode limitMode,
        String limitValue,
        Instant validFrom,
        Instant validTo
) {
}
