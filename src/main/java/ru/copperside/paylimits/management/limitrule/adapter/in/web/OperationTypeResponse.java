package ru.copperside.paylimits.management.limitrule.adapter.in.web;

import ru.copperside.paylimits.management.limitrule.domain.OperationType;

import java.time.Instant;
import java.util.UUID;

public record OperationTypeResponse(
        UUID id,
        String code,
        String name,
        String familyCode,
        String direction,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    public static OperationTypeResponse from(OperationType type) {
        return new OperationTypeResponse(
                type.id(),
                type.code(),
                type.name(),
                type.familyCode(),
                type.direction().name(),
                type.enabled(),
                type.createdAt(),
                type.updatedAt()
        );
    }
}
