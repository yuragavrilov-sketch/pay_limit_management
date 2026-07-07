package ru.copperside.paylimits.management.audit.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import ru.copperside.paylimits.management.audit.domain.AuditEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of an audit event. {@code before}/{@code after} are emitted as raw JSON objects
 * (parsed from the stored {@code jsonb} text), or {@code null} when absent. Uses the Jackson 3
 * tree type so the Spring MVC (Jackson 3) serializer renders them as nested objects.
 */
public record AuditEventResponse(
        UUID id,
        String entityType,
        String entityId,
        String action,
        String actorId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String actorName,
        Instant occurredAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode before,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode after
) {

    static AuditEventResponse from(AuditEvent event, ObjectMapper objectMapper) {
        return new AuditEventResponse(
                event.id(),
                event.entityType(),
                event.entityId(),
                event.action(),
                event.actorId(),
                event.actorName(),
                event.occurredAt(),
                toJson(event.beforeJson(), objectMapper),
                toJson(event.afterJson(), objectMapper));
    }

    private static JsonNode toJson(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot parse stored audit JSON", e);
        }
    }
}
