package ru.copperside.paylimits.management.audit.application;

import ru.copperside.paylimits.management.audit.application.port.out.AuditEventRepository;
import ru.copperside.paylimits.management.audit.application.port.out.AuditPayloadSerializer;
import ru.copperside.paylimits.management.audit.domain.AuditEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

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

    /**
     * Runs a single mutating {@code write} and immediately appends its audit event, returning the
     * written entity. This binds the "persist + audit" pair into one call so a mutation cannot be
     * committed without its audit row (the copy-paste of {@code write(); record(...); return;} at each
     * simple mutation site is replaced by one invocation). Both run inside whatever transaction the
     * caller already opened, so mutation and audit still commit or roll back together (MGT-I-01).
     *
     * @param entityId derives the audit {@code entityId} from the freshly written entity (e.g.
     *                 {@code e -> e.id().toString()}); evaluated after the write so it can read the
     *                 persisted identity
     * @param write    the persistence call whose result is both returned and audited as {@code after}
     */
    public <T> T writeAndRecord(
            String entityType,
            String action,
            Object before,
            Function<T, String> entityId,
            Supplier<T> write) {
        T result = write.get();
        record(entityType, entityId.apply(result), action, before, result);
        return result;
    }
}
