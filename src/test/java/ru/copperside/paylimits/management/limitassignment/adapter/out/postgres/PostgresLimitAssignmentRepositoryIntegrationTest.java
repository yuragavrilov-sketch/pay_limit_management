package ru.copperside.paylimits.management.limitassignment.adapter.out.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignment;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignmentProblemException;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PostgresLimitAssignmentRepositoryIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostgresLimitAssignmentRepository repository;

    @Test
    void savesListsAndUpdatesAssignments() {
        UUID ruleId = insertActiveRule("RULE_ASSIGNMENT_SAVE");
        LimitAssignment assignment = assignment(ruleId, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.LIMITED,
                Instant.parse("2026-05-29T00:00:00Z"), null, true);

        repository.saveAssignment(assignment);
        LimitAssignment updated = new LimitAssignment(
                assignment.id(),
                assignment.ruleId(),
                assignment.ownerType(),
                assignment.ownerId(),
                LimitMode.BLOCKED,
                assignment.validFrom(),
                assignment.validTo(),
                false,
                assignment.createdAt(),
                Instant.parse("2026-05-29T10:05:00Z")
        );

        assertThat(repository.findAssignment(assignment.id())).contains(assignment);
        assertThat(repository.listAssignments()).contains(assignment);
        assertThat(repository.updateAssignment(updated)).isEqualTo(updated);
        assertThat(repository.findAssignment(assignment.id())).contains(updated);
    }

    @Test
    void findsRuleAndMerchantGroupReferences() {
        UUID ruleId = insertActiveRule("RULE_ASSIGNMENT_REFERENCES");
        UUID groupId = insertMerchantGroup(true);

        assertThat(repository.findRule(ruleId)).hasValueSatisfying(rule -> assertThat(rule.active()).isTrue());
        assertThat(repository.findMerchantGroup(groupId)).hasValueSatisfying(group -> assertThat(group.enabled()).isTrue());
    }

    @Test
    void detectsEnabledOverlapAndAllowsAdjacentPeriods() {
        UUID ruleId = insertActiveRule("RULE_ASSIGNMENT_OVERLAP_QUERY");
        LimitAssignment first = assignment(ruleId, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.UNLIMITED,
                Instant.parse("2026-05-29T00:00:00Z"),
                Instant.parse("2026-05-30T00:00:00Z"),
                true);
        repository.saveAssignment(first);

        assertThat(repository.hasEnabledOverlap(null, ruleId, AssignmentOwnerType.MERCHANT, "502118",
                Instant.parse("2026-05-29T12:00:00Z"), null)).isTrue();
        assertThat(repository.hasEnabledOverlap(null, ruleId, AssignmentOwnerType.MERCHANT, "502118",
                Instant.parse("2026-05-30T00:00:00Z"), null)).isFalse();
    }

    @Test
    void mapsEnabledOverlapConstraintToAssignmentConflict() {
        UUID ruleId = insertActiveRule("RULE_ASSIGNMENT_OVERLAP_CONSTRAINT");
        repository.saveAssignment(assignment(ruleId, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.UNLIMITED,
                Instant.parse("2026-05-29T00:00:00Z"), null, true));

        assertThatThrownBy(() -> repository.saveAssignment(assignment(ruleId, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.BLOCKED,
                Instant.parse("2026-05-30T00:00:00Z"), null, true)))
                .isInstanceOf(LimitAssignmentProblemException.class)
                .hasMessageContaining("ASSIGNMENT_CONFLICT");
    }

    private UUID insertActiveRule(String code) {
        UUID ruleId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into limit_management.limit_rules
                    (id, code, version, name, direction,
                     attribute_selector_type, attribute_selector_value, target_type,
                     metric, period, aggregation_scope, currency, interval_minutes,
                     limit_value, error_message_template,
                     status, created_at, updated_at, activated_at, disabled_at)
                values (?, ?, 1, ?, 'IN',
                        'NONE', null, 'PHONE',
                        'AMOUNT', 'DAY', 'OWNER', 'RUB', null,
                        1000.00, 'template',
                        'ACTIVE', now(), now(), now(), null)
                """, ruleId, code, code);
        jdbcTemplate.update("""
                insert into limit_management.limit_rule_operation_type (rule_id, operation_type_code)
                values (?, 'SBP_C2B')
                """, ruleId);
        return ruleId;
    }

    private UUID insertMerchantGroup(boolean enabled) {
        UUID typeId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into limit_management.merchant_group_types
                    (id, code, name, description, enabled, sort_order, created_at, updated_at)
                values (?, ?, ?, null, true, 10, now(), now())
                """, typeId, "type-" + groupId, "Type " + groupId);
        jdbcTemplate.update("""
                insert into limit_management.merchant_groups
                    (id, type_id, code, name, description, enabled, created_at, updated_at)
                values (?, ?, ?, ?, null, ?, now(), now())
                """, groupId, typeId, "group-" + groupId, "Group " + groupId, enabled);
        return groupId;
    }

    private LimitAssignment assignment(
            UUID ruleId,
            AssignmentOwnerType ownerType,
            String ownerId,
            LimitMode mode,
            Instant validFrom,
            Instant validTo,
            boolean enabled
    ) {
        return new LimitAssignment(
                UUID.randomUUID(),
                ruleId,
                ownerType,
                ownerId,
                mode,
                validFrom,
                validTo,
                enabled,
                Instant.parse("2026-05-29T10:00:00Z"),
                Instant.parse("2026-05-29T10:00:00Z")
        );
    }
}
