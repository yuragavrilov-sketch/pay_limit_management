package ru.copperside.paylimits.management.runtimeconfig.domain;

import java.time.Instant;

/**
 * Lightweight projection of the LATEST compiled runtime manifest's size, used only for the
 * manifest-size and manifest-age observability gauges (spec §7). Deliberately narrower than the
 * full {@link RuntimeManifest} (no payload/rules/assignments/memberships) so a metrics scrape never
 * pays for deserializing the canonical JSON body.
 */
public record RuntimeManifestSizeSnapshot(
        int ruleCount,
        int assignmentCount,
        int membershipCount,
        Instant createdAt
) {
}
