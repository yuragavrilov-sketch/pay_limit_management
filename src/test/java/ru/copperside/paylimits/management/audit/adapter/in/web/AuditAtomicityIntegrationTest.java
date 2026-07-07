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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.audit.OperatorHeaderTestConfig;
import ru.copperside.paylimits.management.audit.application.port.out.AuditEventRepository;
import ru.copperside.paylimits.management.audit.domain.AuditEvent;
import ru.copperside.paylimits.management.audit.adapter.out.postgres.PostgresAuditEventRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MGT-I-01 (same-transaction audit) and its atomicity corollary against a real Postgres. A successful
 * mutation writes both the entity row and exactly one audit_event row; when the audit append is forced
 * to fail mid-transaction, the whole unit of work rolls back, leaving neither an entity row nor an
 * orphan audit row — proving mutation and audit share one transaction. Uses synthetic identifiers only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({OperatorHeaderTestConfig.class, AuditAtomicityIntegrationTest.ControllableAuditConfig.class})
class AuditAtomicityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "limit_management");
        registry.add("spring.flyway.default-schema", () -> "limit_management");
    }

    private static final String CREATE_OPERATION_TYPE = """
            {
              "code": "%s",
              "name": "SBP C2B",
              "familyCode": "SBP",
              "direction": "IN",
              "counterpartyType": "PHONE"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ControllableAuditEventRepository auditRepository;

    @BeforeEach
    void clean() {
        auditRepository.setFail(false);
        jdbcTemplate.update("delete from limit_management.audit_event");
        jdbcTemplate.update("delete from limit_management.operation_types");
    }

    // MGT-I-01: a successful create writes the entity row AND exactly one matching audit_event row.
    @Test
    void mutationWritesEntityAndAuditInSameTransaction() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/operation-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_OPERATION_TYPE.formatted("SBP_C2B_TX")))
                .andExpect(status().isOk());

        Integer operationTypes = jdbcTemplate.queryForObject(
                "select count(*) from limit_management.operation_types where code = ?", Integer.class, "SBP_C2B_TX");
        assertThat(operationTypes).isEqualTo(1);

        List<AuditEvent> events = auditRepository.find("OPERATION_TYPE", null, null, null, 0, 50);
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.action()).isEqualTo("CREATE");
        assertThat(event.actorId()).isEqualTo(OperatorHeaderTestConfig.OPERATOR_ID);
        assertThat(event.actorName()).isEqualTo(OperatorHeaderTestConfig.OPERATOR_NAME);
        assertThat(event.beforeJson()).isNull();
        assertThat(event.afterJson()).contains("SBP_C2B_TX");

        Integer auditRows = jdbcTemplate.queryForObject(
                "select count(*) from limit_management.audit_event", Integer.class);
        assertThat(auditRows).isEqualTo(1);
    }

    // MGT-I-01 atomicity: when the audit append throws mid-transaction, the mutation rolls back too —
    // no operation_types row and no audit_event row are left behind.
    @Test
    void auditFailureRollsBackTheMutation() {
        auditRepository.setFail(true);

        // The forced failure propagates out of the (rolled-back) transaction; MockMvc rethrows it since
        // no handler maps it. The point of the test is the DB state afterwards, not the HTTP status.
        Throwable thrown = catchThrowable(() -> mockMvc.perform(post("/internal/v1/limit-management/operation-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_OPERATION_TYPE.formatted("SBP_C2B_ROLLBACK"))));
        assertThat(thrown).isNotNull();

        Integer operationTypes = jdbcTemplate.queryForObject(
                "select count(*) from limit_management.operation_types where code = ?",
                Integer.class, "SBP_C2B_ROLLBACK");
        assertThat(operationTypes).isZero();

        Integer auditRows = jdbcTemplate.queryForObject(
                "select count(*) from limit_management.audit_event", Integer.class);
        assertThat(auditRows).isZero();
    }

    /**
     * Wraps the real {@link PostgresAuditEventRepository} so a test can force {@code append} to throw
     * inside the service transaction. Reads are always delegated.
     */
    static class ControllableAuditEventRepository implements AuditEventRepository {

        private final AuditEventRepository delegate;
        private volatile boolean fail;

        ControllableAuditEventRepository(AuditEventRepository delegate) {
            this.delegate = delegate;
        }

        void setFail(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void append(AuditEvent event) {
            if (fail) {
                throw new IllegalStateException("Forced audit append failure");
            }
            delegate.append(event);
        }

        @Override
        public List<AuditEvent> find(String entityType, String entityId, Instant from, Instant to, int page, int size) {
            return delegate.find(entityType, entityId, from, to, page, size);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControllableAuditConfig {

        @Bean
        @Primary
        ControllableAuditEventRepository controllableAuditEventRepository(PostgresAuditEventRepository delegate) {
            return new ControllableAuditEventRepository(delegate);
        }
    }
}
