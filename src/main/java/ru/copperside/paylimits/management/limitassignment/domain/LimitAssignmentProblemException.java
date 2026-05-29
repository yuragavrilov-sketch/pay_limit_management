package ru.copperside.paylimits.management.limitassignment.domain;

public class LimitAssignmentProblemException extends RuntimeException {

    private final String code;
    private final Object details;

    public LimitAssignmentProblemException(String code, String message) {
        this(code, message, null);
    }

    public LimitAssignmentProblemException(String code, String message, Object details) {
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
