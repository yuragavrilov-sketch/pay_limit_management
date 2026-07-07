package ru.copperside.paylimits.management.audit.domain;

/**
 * Raised when a mutating request lacks the required operator identity.
 * {@code GlobalExceptionHandler} maps {@code OPERATOR_ID_REQUIRED} to HTTP 400.
 */
public class OperatorProblemException extends RuntimeException {

    private final String code;

    public OperatorProblemException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
