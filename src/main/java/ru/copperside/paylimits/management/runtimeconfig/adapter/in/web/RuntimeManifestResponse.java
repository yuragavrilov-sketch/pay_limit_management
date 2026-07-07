package ru.copperside.paylimits.management.runtimeconfig.adapter.in.web;

import ru.copperside.paylimits.management.runtimeconfig.application.ManifestDocumentV2Mapper;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestStatus;
import ru.copperside.paylimits.management.runtimeconfig.domain.wire.ManifestDocumentV2;

import java.time.Instant;
import java.util.UUID;

/**
 * HTTP body for a single runtime manifest. Carries the engine-facing §4.3 {@link ManifestDocumentV2}
 * as {@code document} — the payload engine canonicalizes and hashes — plus the {@code checksum}
 * alongside it (never inside the document) and management-side lifecycle metadata ({@code id},
 * {@code status}, {@code createdAt}, counts) that is not part of the hashed document.
 */
public record RuntimeManifestResponse(
        UUID id,
        String checksum,
        RuntimeManifestStatus status,
        Instant createdAt,
        int ruleCount,
        int assignmentCount,
        int membershipCount,
        ManifestDocumentV2 document
) {
    public static RuntimeManifestResponse from(RuntimeManifest manifest) {
        return new RuntimeManifestResponse(
                manifest.id(),
                manifest.checksum(),
                manifest.status(),
                manifest.createdAt(),
                manifest.ruleCount(),
                manifest.assignmentCount(),
                manifest.membershipCount(),
                ManifestDocumentV2Mapper.toDocument(manifest.payload())
        );
    }
}
