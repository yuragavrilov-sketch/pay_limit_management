package ru.copperside.paylimits.management.merchantgroup.domain;

public class MerchantGroupProblemException extends RuntimeException {

    private final String code;

    public MerchantGroupProblemException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
