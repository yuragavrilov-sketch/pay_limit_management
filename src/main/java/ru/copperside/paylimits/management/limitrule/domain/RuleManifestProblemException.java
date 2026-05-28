package ru.copperside.paylimits.management.limitrule.domain;

import java.util.List;

public class RuleManifestProblemException extends RuntimeException {

    private final String code;
    private final RuleManifestDiagnosticsDetails details;

    public RuleManifestProblemException(String code, String message) {
        this(code, message, List.of());
    }

    public RuleManifestProblemException(String code, String message, List<ManifestDiagnostic> diagnostics) {
        super(code + ": " + message);
        this.code = code;
        this.details = new RuleManifestDiagnosticsDetails(diagnostics);
    }

    public String code() {
        return code;
    }

    public List<ManifestDiagnostic> diagnostics() {
        return details.diagnostics();
    }

    public RuleManifestDiagnosticsDetails details() {
        return details;
    }
}
