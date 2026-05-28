package ru.copperside.paylimits.management.limitrule.domain;

import java.time.Instant;
import java.util.List;

public record RuleManifestPayload(
        int version,
        RuleManifestStatus status,
        int ruleCount,
        Instant createdAt,
        List<CompiledRule> rules,
        List<ManifestDiagnostic> diagnostics
) {
    public RuleManifestPayload {
        rules = List.copyOf(rules);
        diagnostics = List.copyOf(diagnostics);
    }
}
