package ru.copperside.paylimits.management.runtimeconfig.domain.wire;

import java.time.Instant;
import java.util.UUID;

public record AssignmentV2(
        UUID assignmentId,
        UUID ruleId,
        OwnerV2 owner,
        String mode,
        Instant activeFrom,
        Instant activeTo
) {
}
