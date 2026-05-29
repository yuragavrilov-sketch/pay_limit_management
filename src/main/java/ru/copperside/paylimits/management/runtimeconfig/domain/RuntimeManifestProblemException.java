package ru.copperside.paylimits.management.runtimeconfig.domain;

public class RuntimeManifestProblemException extends RuntimeException {

    private final String code;
    private final Object details;

    public RuntimeManifestProblemException(String code, String message) {
        this(code, message, null);
    }

    public RuntimeManifestProblemException(String code, String message, Object details) {
        super(code + ": " + message);
        this.code = code;
        this.details = details;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
