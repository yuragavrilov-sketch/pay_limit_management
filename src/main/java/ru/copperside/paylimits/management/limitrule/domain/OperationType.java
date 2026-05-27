package ru.copperside.paylimits.management.limitrule.domain;

import java.time.Instant;
import java.util.UUID;

public record OperationType(
        UUID id,
        String code,
        String name,
        String familyCode,
        OperationDirection direction,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
