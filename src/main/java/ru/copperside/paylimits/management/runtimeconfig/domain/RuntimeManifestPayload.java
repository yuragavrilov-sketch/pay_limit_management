package ru.copperside.paylimits.management.runtimeconfig.domain;

import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;

import java.time.Instant;
import java.util.List;

public record RuntimeManifestPayload(
        int version,
        RuntimeManifestStatus status,
        Instant createdAt,
        Instant effectiveFrom,
        int ruleCount,
        int assignmentCount,
        int membershipCount,
        List<RuntimeCompiledRule> rules,
        List<RuntimeCompiledAssignment> assignments,
        List<RuntimeMerchantGroupMembership> memberships,
        List<ManifestDiagnostic> diagnostics
) {
    public RuntimeManifestPayload {
        rules = List.copyOf(rules);
        assignments = List.copyOf(assignments);
        memberships = List.copyOf(memberships);
        diagnostics = List.copyOf(diagnostics);
    }
}
