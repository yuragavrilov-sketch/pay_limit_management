package ru.copperside.paylimits.management.limitassignment.application;

import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;

import java.time.Instant;

public record PatchLimitAssignmentCommand(
        LimitMode limitMode,
        Instant validFrom,
        Instant validTo,
        Boolean enabled
) {
}
