package ru.copperside.paylimits.management.limitrule.domain;

import java.time.Instant;

public record DictionaryItem(
        String code,
        String name,
        boolean enabled,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt
) {
}
