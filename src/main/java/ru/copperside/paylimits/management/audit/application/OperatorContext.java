package ru.copperside.paylimits.management.audit.application;

/**
 * Operator identity for the current request. Spring-free port; the web adapter supplies a
 * request-scoped implementation populated from the {@code X-Operator-Id}/{@code X-Operator-Name}
 * headers.
 */
public interface OperatorContext {

    String operatorId();

    String operatorName();
}
