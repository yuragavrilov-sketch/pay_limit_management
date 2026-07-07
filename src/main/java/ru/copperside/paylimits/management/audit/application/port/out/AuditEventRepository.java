package ru.copperside.paylimits.management.audit.application.port.out;

import ru.copperside.paylimits.management.audit.domain.AuditEvent;

import java.time.Instant;
import java.util.List;

/**
 * Append-only store for audit events. {@link #append(AuditEvent)} is the sole write path;
 * there is deliberately no update or delete.
 */
public interface AuditEventRepository {

    void append(AuditEvent event);

    /**
     * Reads audit events filtered by any combination of {@code entityType}, {@code entityId},
     * {@code from} and {@code to} (all optional/nullable), ordered by {@code occurredAt} desc,
     * paginated by zero-based {@code page} and {@code size}.
     */
    List<AuditEvent> find(String entityType, String entityId, Instant from, Instant to, int page, int size);
}
