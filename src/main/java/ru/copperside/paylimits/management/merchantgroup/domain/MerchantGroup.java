package ru.copperside.paylimits.management.merchantgroup.domain;

import java.time.Instant;
import java.util.UUID;

public record MerchantGroup(
        UUID id,
        UUID typeId,
        String code,
        String name,
        String description,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
