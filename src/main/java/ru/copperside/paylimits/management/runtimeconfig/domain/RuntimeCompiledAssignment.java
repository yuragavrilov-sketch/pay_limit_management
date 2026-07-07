package ru.copperside.paylimits.management.runtimeconfig.domain;

import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;

import java.time.Instant;
import java.util.UUID;

public record RuntimeCompiledAssignment(
        UUID assignmentId,
        UUID ruleId,
        String ruleCode,
        AssignmentOwnerType ownerType,
        String ownerId,
        LimitMode limitMode,
        Instant validFrom,
        Instant validTo
) {
}
