package ru.copperside.paylimits.management.merchantgroup.domain;

import java.time.Instant;
import java.util.UUID;

public record MerchantGroupType(
        UUID id,
        String code,
        String name,
        String description,
        boolean enabled,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt
) {
}
