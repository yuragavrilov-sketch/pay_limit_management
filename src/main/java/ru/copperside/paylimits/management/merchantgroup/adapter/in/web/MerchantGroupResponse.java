package ru.copperside.paylimits.management.merchantgroup.adapter.in.web;

import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroup;

import java.time.Instant;
import java.util.UUID;

public record MerchantGroupResponse(
        UUID id,
        UUID typeId,
        String code,
        String name,
        String description,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    static MerchantGroupResponse from(MerchantGroup group) {
        return new MerchantGroupResponse(
                group.id(),
                group.typeId(),
                group.code(),
                group.name(),
                group.description(),
                group.enabled(),
                group.createdAt(),
                group.updatedAt()
        );
    }
}
