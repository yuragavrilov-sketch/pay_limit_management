package ru.copperside.paylimits.management.audit;

import ru.copperside.paylimits.management.audit.application.AuditRecorder;
import ru.copperside.paylimits.management.audit.application.OperatorContext;
import ru.copperside.paylimits.management.audit.application.port.out.AuditEventRepository;
import ru.copperside.paylimits.management.audit.application.port.out.AuditPayloadSerializer;
import ru.copperside.paylimits.management.audit.domain.AuditEvent;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fixtures for wiring audit into datasource-excluded slice tests and pure unit tests: an in-memory
 * recording {@link AuditEventRepository}, a real Jackson-backed {@link AuditPayloadSerializer}, a
 * fixed {@link OperatorContext} and an {@link AuditRecorder} factory. The real atomicity (mutation +
 * audit share one DB transaction) is exercised by the Testcontainers integration tests.
 */
public final class AuditTestSupport {

    public static final String OPERATOR_ID = OperatorHeaderTestConfig.OPERATOR_ID;
    public static final String OPERATOR_NAME = OperatorHeaderTestConfig.OPERATOR_NAME;

    private AuditTestSupport() {
    }

    /** In-memory, append-only recorder that lets tests assert on the events a mutation produced. */
    public static final class RecordingAuditEventRepository implements AuditEventRepository {

        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void append(AuditEvent event) {
            events.add(event);
        }

        @Override
        public List<AuditEvent> find(String entityType, String entityId, Instant from, Instant to, int page, int size) {
            return events.stream()
                    .filter(e -> entityType == null || entityType.equals(e.entityType()))
                    .filter(e -> entityId == null || entityId.equals(e.entityId()))
                    .filter(e -> from == null || !e.occurredAt().isBefore(from))
                    .filter(e -> to == null || e.occurredAt().isBefore(to))
                    .skip((long) page * size)
                    .limit(size)
                    .toList();
        }

        public List<AuditEvent> events() {
            return List.copyOf(events);
        }

        public void clear() {
            events.clear();
        }
    }

    public static AuditPayloadSerializer jsonSerializer() {
        JsonMapper mapper = JsonMapper.builder().build();
        return value -> value == null ? null : mapper.writeValueAsString(value);
    }

    public static OperatorContext operatorContext(String operatorId, String operatorName) {
        return new OperatorContext() {
            @Override
            public String operatorId() {
                return operatorId;
            }

            @Override
            public String operatorName() {
                return operatorName;
            }
        };
    }

    /** Builds an {@link AuditRecorder} backed by {@code repository}, a fixed operator and a real serializer. */
    public static AuditRecorder recorder(AuditEventRepository repository, Clock clock) {
        return new AuditRecorder(
                operatorContext(OPERATOR_ID, OPERATOR_NAME),
                repository,
                jsonSerializer(),
                clock);
    }
}
