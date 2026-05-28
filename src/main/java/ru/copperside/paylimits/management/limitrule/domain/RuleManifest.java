package ru.copperside.paylimits.management.limitrule.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RuleManifest(
        UUID id,
        int version,
        RuleManifestStatus status,
        String checksum,
        int ruleCount,
        Instant createdAt,
        List<CompiledRule> rules,
        List<ManifestDiagnostic> diagnostics
) {
    public RuleManifest {
        rules = List.copyOf(rules);
        diagnostics = List.copyOf(diagnostics);
    }
}
