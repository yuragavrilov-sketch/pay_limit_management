package ru.copperside.paylimits.management.audit.application;

import ru.copperside.paylimits.management.audit.application.port.out.AuditEventRepository;
import ru.copperside.paylimits.management.audit.domain.AuditEvent;

import java.time.Instant;
import java.util.List;

/**
 * Read-side use case for the audit log. Writing is done by the mutation flows (Task 2)
 * through {@link AuditEventRepository#append}; this service only exposes queries.
 */
public class AuditEventService {

    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 200;

    private final AuditEventRepository repository;

    public AuditEventService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public List<AuditEvent> find(String entityType, String entityId, Instant from, Instant to, Integer page, Integer size) {
        int effectivePage = page == null || page < 0 ? 0 : page;
        int effectiveSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return repository.find(
                blankToNull(entityType),
                blankToNull(entityId),
                from,
                to,
                effectivePage,
                effectiveSize);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
