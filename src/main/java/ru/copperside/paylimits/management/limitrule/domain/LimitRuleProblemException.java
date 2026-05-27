package ru.copperside.paylimits.management.limitrule.domain;

public class LimitRuleProblemException extends RuntimeException {

    private final String code;

    public LimitRuleProblemException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
