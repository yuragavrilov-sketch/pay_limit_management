package ru.copperside.paylimits.management.limitrule.domain;

import java.time.Instant;
import java.util.UUID;

public record OperationType(
        UUID id,
        String code,
        String name,
        String familyCode,
        OperationDirection direction,
        CounterpartyType counterpartyType,
        boolean enabled,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt
) {
}
