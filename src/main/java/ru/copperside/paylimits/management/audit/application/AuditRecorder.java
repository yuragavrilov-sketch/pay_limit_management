package ru.copperside.paylimits.management.audit.application;

import ru.copperside.paylimits.management.audit.application.port.out.AuditEventRepository;
import ru.copperside.paylimits.management.audit.application.port.out.AuditPayloadSerializer;
import ru.copperside.paylimits.management.audit.domain.AuditEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Spring-free application helper that assembles and appends an {@link AuditEvent} for a mutating
 * use case. It bundles the three injected collaborators every mutation needs — {@link OperatorContext}
 * (actor identity for the current request), {@link AuditEventRepository} (the append-only write path)
 * and {@link AuditPayloadSerializer} (before/after snapshots) — plus the injectable {@link Clock}.
 *
 * <p>{@link #record} performs no transaction management of its own: callers invoke it INSIDE the same
 * {@code TransactionRunner.run(...)} unit of work as the mutation, so the change and its audit row
 * commit or roll back together (MGT-I-01).
 */
public final class AuditRecorder {

    private final OperatorContext operatorContext;
    private final AuditEventRepository repository;
    private final AuditPayloadSerializer serializer;
    private final Clock clock;

    public AuditRecorder(
            OperatorContext operatorContext,
            AuditEventRepository repository,
            AuditPayloadSerializer serializer,
            Clock clock
    ) {
        this.operatorContext = operatorContext;
        this.repository = repository;
        this.serializer = serializer;
        this.clock = clock;
    }

    /**
     * Appends one audit event describing a mutation. {@code before}/{@code after} are the domain
     * entity snapshots before and after the change (either may be {@code null}, e.g. {@code before}
     * for a CREATE); each is serialized to JSON via the {@link AuditPayloadSerializer}.
     */
    public void record(String entityType, String entityId, String action, Object before, Object after) {
        repository.append(new AuditEvent(
                UUID.randomUUID(),
                entityType,
                entityId,
                action,
                operatorContext.operatorId(),
                operatorContext.operatorName(),
                Instant.now(clock),
                serializer.toJson(before),
                serializer.toJson(after)));
    }
}
