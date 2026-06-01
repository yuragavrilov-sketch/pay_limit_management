package ru.copperside.paylimits.management.runtimeconfig.adapter.in.web;

import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor;

import java.time.Instant;
import java.util.UUID;

public record RuntimeManifestScheduledDescriptorResponse(
        UUID id,
        int version,
        String checksum,
        Instant createdAt,
        Instant effectiveFrom
) {
    public static RuntimeManifestScheduledDescriptorResponse from(RuntimeManifestDescriptor descriptor) {
        return new RuntimeManifestScheduledDescriptorResponse(
                descriptor.id(),
                descriptor.version(),
                descriptor.checksum(),
                descriptor.createdAt(),
                descriptor.effectiveFrom()
        );
    }
}
