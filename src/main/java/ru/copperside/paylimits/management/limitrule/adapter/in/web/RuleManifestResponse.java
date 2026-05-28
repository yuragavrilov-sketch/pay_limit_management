package ru.copperside.paylimits.management.limitrule.adapter.in.web;

import ru.copperside.paylimits.management.limitrule.domain.CompiledRule;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifest;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RuleManifestResponse(
        UUID id,
        int version,
        RuleManifestStatus status,
        String checksum,
        int ruleCount,
        Instant createdAt,
        List<CompiledRule> rules,
        List<ManifestDiagnostic> diagnostics
) {
    public static RuleManifestResponse from(RuleManifest manifest) {
        return new RuleManifestResponse(
                manifest.id(),
                manifest.version(),
                manifest.status(),
                manifest.checksum(),
                manifest.ruleCount(),
                manifest.createdAt(),
                manifest.rules(),
                manifest.diagnostics()
        );
    }
}
