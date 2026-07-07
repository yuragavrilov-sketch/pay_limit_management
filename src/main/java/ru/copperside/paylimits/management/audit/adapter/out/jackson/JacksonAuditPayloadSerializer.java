package ru.copperside.paylimits.management.audit.adapter.out.jackson;

import org.springframework.stereotype.Component;
import ru.copperside.paylimits.management.audit.application.port.out.AuditPayloadSerializer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson 3 ({@code tools.jackson}) implementation of {@link AuditPayloadSerializer}. Owns a
 * private {@link ObjectMapper} rather than injecting the shared web one, mirroring how
 * {@code AuditEventController} and the manifest repository build their own mappers. Records
 * serialize in declaration order, so the output is deterministic without extra configuration;
 * Jackson 3 auto-registers java-time support, emitting {@link java.time.Instant} as ISO-8601.
 */
@Component
public class JacksonAuditPayloadSerializer implements AuditPayloadSerializer {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Override
    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize audit payload", e);
        }
    }
}
