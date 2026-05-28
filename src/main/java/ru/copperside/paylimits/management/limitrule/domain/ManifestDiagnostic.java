package ru.copperside.paylimits.management.limitrule.domain;

import java.util.List;
import java.util.UUID;

public record ManifestDiagnostic(
        String code,
        ManifestDiagnosticSeverity severity,
        String message,
        List<UUID> ruleIds,
        String path
) {
    public ManifestDiagnostic {
        ruleIds = List.copyOf(ruleIds);
    }
}
