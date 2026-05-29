package ru.copperside.paylimits.management.runtimeconfig.domain;

import ru.copperside.paylimits.management.limitrule.domain.CompiledRule;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RuntimeManifest(
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
        List<ManifestDiagnostic> diagnostics,
        RuntimeManifestPayload payload
) {
    public RuntimeManifest {
        rules = List.copyOf(rules);
        assignments = List.copyOf(assignments);
        memberships = List.copyOf(memberships);
        diagnostics = List.copyOf(diagnostics);
    }
}
