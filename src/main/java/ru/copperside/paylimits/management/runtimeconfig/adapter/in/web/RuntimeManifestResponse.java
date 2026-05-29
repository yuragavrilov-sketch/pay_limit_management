package ru.copperside.paylimits.management.runtimeconfig.adapter.in.web;

import ru.copperside.paylimits.management.limitrule.domain.CompiledRule;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestStatus;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RuntimeManifestResponse(
        UUID id,
        int version,
        RuntimeManifestStatus status,
        String checksum,
        Instant createdAt,
        Instant effectiveFrom,
        int ruleCount,
        int assignmentCount,
        int membershipCount,
        List<CompiledRule> rules,
        List<RuntimeCompiledAssignment> assignments,
        List<RuntimeMerchantGroupMembership> memberships,
        List<ManifestDiagnostic> diagnostics
) {
    public static RuntimeManifestResponse from(RuntimeManifest manifest) {
        return new RuntimeManifestResponse(
                manifest.id(),
                manifest.version(),
                manifest.status(),
                manifest.checksum(),
                manifest.createdAt(),
                manifest.effectiveFrom(),
                manifest.ruleCount(),
                manifest.assignmentCount(),
                manifest.membershipCount(),
                manifest.rules(),
                manifest.assignments(),
                manifest.memberships(),
                manifest.diagnostics()
        );
    }
}
