package ru.copperside.paylimits.management.audit.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable, append-only audit record. {@code beforeJson}/{@code afterJson} carry the
 * entity snapshots as raw JSON text (nullable); they are persisted into {@code jsonb} columns.
 */
public record AuditEvent(
        UUID id,
        String entityType,
        String entityId,
        String action,
        String actorId,
        String actorName,
        Instant occurredAt,
        String beforeJson,
        String afterJson
) {
}
