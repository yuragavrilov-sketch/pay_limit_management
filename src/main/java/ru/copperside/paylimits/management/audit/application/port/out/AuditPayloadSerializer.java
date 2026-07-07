package ru.copperside.paylimits.management.audit.application.port.out;

/**
 * Serializes a domain entity snapshot to a stable JSON string for the {@code before}/{@code after}
 * columns of an audit event. Spring-free application port; the web/persistence layer supplies a
 * Jackson-backed implementation.
 */
public interface AuditPayloadSerializer {

    /**
     * Serializes {@code value} to a JSON string, or returns {@code null} when {@code value} is
     * {@code null} (used for the {@code before} snapshot of CREATE-style actions).
     */
    String toJson(Object value);
}
