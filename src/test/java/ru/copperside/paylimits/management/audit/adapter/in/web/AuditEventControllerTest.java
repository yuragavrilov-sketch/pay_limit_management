package ru.copperside.paylimits.management.audit.adapter.in.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.copperside.paylimits.management.audit.application.port.out.AuditEventRepository;
import ru.copperside.paylimits.management.audit.domain.AuditEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deliberately does NOT import {@code OperatorHeaderTestConfig}, so the missing-header path is
 * exercised for real.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(AuditEventControllerTest.TestSupport.class)
class AuditEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeAuditEventRepository repository;

    @BeforeEach
    void setUp() {
        repository.clear();
    }

    @Test
    void rejectsMutatingRequestWithoutOperatorId() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("OPERATOR_ID_REQUIRED"));
    }

    @Test
    void rejectsMutatingRequestWithBlankOperatorId() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/rules")
                        .header("X-Operator-Id", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("OPERATOR_ID_REQUIRED"));
    }

    @Test
    void listsAuditEventsWithBeforeAfterAsJsonObjects() throws Exception {
        repository.append(new AuditEvent(
                UUID.randomUUID(),
                "LIMIT_RULE",
                "RULE_SBP_DAY",
                "UPDATE",
                "op-001",
                "Jane Operator",
                Instant.parse("2026-07-01T10:00:00Z"),
                "{\"status\":\"DRAFT\"}",
                "{\"status\":\"ACTIVE\"}"));

        mockMvc.perform(get("/internal/v1/limit-management/audit-events")
                        .param("entityType", "LIMIT_RULE")
                        .param("entityId", "RULE_SBP_DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].entityType").value("LIMIT_RULE"))
                .andExpect(jsonPath("$.data[0].entityId").value("RULE_SBP_DAY"))
                .andExpect(jsonPath("$.data[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.data[0].actorId").value("op-001"))
                .andExpect(jsonPath("$.data[0].actorName").value("Jane Operator"))
                .andExpect(jsonPath("$.data[0].before.status").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].after.status").value("ACTIVE"));
    }

    @Test
    void auditEventsReadIsAllowedWithoutOperatorId() throws Exception {
        mockMvc.perform(get("/internal/v1/limit-management/audit-events"))
                .andExpect(status().isOk());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSupport {

        @Bean
        @Primary
        FakeAuditEventRepository fakeAuditEventRepository() {
            return new FakeAuditEventRepository();
        }

        @Bean("testAuditEventService")
        @Primary
        ru.copperside.paylimits.management.audit.application.AuditEventService auditEventService(
                FakeAuditEventRepository repository
        ) {
            return new ru.copperside.paylimits.management.audit.application.AuditEventService(repository);
        }
    }

    static class FakeAuditEventRepository implements AuditEventRepository {

        private final List<AuditEvent> events = new ArrayList<>();

        void clear() {
            events.clear();
        }

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
    }
}
