package ru.copperside.paylimits.management.runtimeconfig.domain;

import java.time.Instant;
import java.util.UUID;

public record RuntimeManifestDescriptor(
        UUID id,
        int version,
        String checksum,
        Instant createdAt,
        Instant effectiveFrom,
        RuntimeManifestLifecycleStatus lifecycleStatus
) {
}
