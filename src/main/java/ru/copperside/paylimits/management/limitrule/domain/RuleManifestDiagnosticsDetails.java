package ru.copperside.paylimits.management.limitrule.domain;

import java.util.List;

public record RuleManifestDiagnosticsDetails(List<ManifestDiagnostic> diagnostics) {
    public RuleManifestDiagnosticsDetails {
        diagnostics = List.copyOf(diagnostics);
    }
}
