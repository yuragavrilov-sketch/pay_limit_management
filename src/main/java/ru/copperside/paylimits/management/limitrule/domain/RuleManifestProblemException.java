package ru.copperside.paylimits.management.limitrule.domain;

import java.util.List;

public class RuleManifestProblemException extends RuntimeException {

    private final String code;
    private final List<ManifestDiagnostic> diagnostics;

    public RuleManifestProblemException(String code, String message) {
        this(code, message, List.of());
    }

    public RuleManifestProblemException(String code, String message, List<ManifestDiagnostic> diagnostics) {
        super(code + ": " + message);
        this.code = code;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public String code() {
        return code;
    }

    public List<ManifestDiagnostic> diagnostics() {
        return diagnostics;
    }
}
