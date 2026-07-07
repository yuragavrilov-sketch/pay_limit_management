package ru.copperside.paylimits.management.limitassignment.adapter.in.web;

import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignment;

import java.time.Instant;
import java.util.UUID;

public record LimitAssignmentResponse(
        UUID id,
        UUID ruleId,
        String ownerType,
        String ownerId,
        String limitMode,
        Instant validFrom,
        Instant validTo,
        boolean enabled
) {
    public static LimitAssignmentResponse from(LimitAssignment assignment) {
        return new LimitAssignmentResponse(
                assignment.id(),
                assignment.ruleId(),
                assignment.ownerType().name(),
                assignment.ownerId(),
                assignment.limitMode().name(),
                assignment.validFrom(),
                assignment.validTo(),
                assignment.enabled()
        );
    }
}
