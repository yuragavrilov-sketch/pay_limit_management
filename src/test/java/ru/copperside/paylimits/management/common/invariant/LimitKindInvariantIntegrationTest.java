package ru.copperside.paylimits.management.common.invariant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack enforcement of the limit-kind non-overlap invariant at the three write points
 * (spec §2.3, cases MGT-I-03/04/05/07). Boots the real Postgres-backed context and drives the REST
 * endpoints through MockMvc; synthetic merchant identifiers only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LimitKindInvariantIntegrationTest {

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

    // Seed timestamps: memberships/assignments start well in the past and stay open-ended so they
    // are "current" against the system clock the service reads.
    private static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    // ---- MGT-I-03: add member to a group delivering a kind the merchant already receives ----

    @Test
    void addingMembershipThatDuplicatesAKindFromAnotherGroupIsRejected() throws Exception {
        String merchantId = "990003";
        UUID typeA = insertGroupType();
        UUID typeB = insertGroupType();
        UUID groupG = insertGroup(typeA); // requested
        UUID groupH = insertGroup(typeB); // already delivering the kind

        UUID ruleG = insertRule("MGT_I_03_G", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(ruleG, "MERCHANT_GROUP", groupG.toString(), true);
        UUID ruleH = insertRule("MGT_I_03_H", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(ruleH, "MERCHANT_GROUP", groupH.toString(), true);

        insertMembership(merchantId, groupH, typeB, PAST, null);

        mockMvc.perform(post("/internal/v1/limit-management/merchant-group-memberships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "merchantId": "%s", "groupId": "%s", "validFrom": "2025-01-01T00:00:00Z" }
                                """.formatted(merchantId, groupG)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LIMIT_KIND_CONFLICT"))
                .andExpect(jsonPath("$.error.conflicts[0].merchantId").value(merchantId))
                .andExpect(jsonPath("$.error.conflicts[0].limitKind.checkType").value("COUNT_DAY"))
                .andExpect(jsonPath("$.error.conflicts[0].existingGroupId").value(groupH.toString()))
                .andExpect(jsonPath("$.error.conflicts[0].requestedGroupId").value(groupG.toString()));
    }

    // ---- MGT-I-04: assign a conflicting kind to a group whose member already receives it ----

    @Test
    void assigningAConflictingKindToAGroupIsRejected() throws Exception {
        String merchantId = "990004";
        UUID typeA = insertGroupType();
        UUID typeB = insertGroupType();
        UUID groupG = insertGroup(typeA); // assignment target
        UUID groupH = insertGroup(typeB); // already delivering the kind

        // Member of both groups.
        insertMembership(merchantId, groupG, typeA, PAST, null);
        insertMembership(merchantId, groupH, typeB, PAST, null);

        UUID ruleH = insertRule("MGT_I_04_H", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(ruleH, "MERCHANT_GROUP", groupH.toString(), true);

        // Active rule with the same kind, assigned via REST to groupG -> conflict for the shared member.
        UUID ruleG = insertRule("MGT_I_04_G", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));

        mockMvc.perform(post("/internal/v1/limit-management/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ruleId": "%s", "ownerType": "MERCHANT_GROUP", "ownerId": "%s",
                                  "limitMode": "UNLIMITED", "validFrom": "2025-01-01T00:00:00Z" }
                                """.formatted(ruleG, groupG)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LIMIT_KIND_CONFLICT"))
                .andExpect(jsonPath("$.error.conflicts[0].merchantId").value(merchantId))
                .andExpect(jsonPath("$.error.conflicts[0].limitKind.checkType").value("COUNT_DAY"))
                .andExpect(jsonPath("$.error.conflicts[0].existingGroupId").value(groupH.toString()))
                .andExpect(jsonPath("$.error.conflicts[0].requestedGroupId").value(groupG.toString()));
    }

    // ---- MGT-I-05: activate a rule whose group assignments would create a conflict ----

    @Test
    void activatingARuleThatCreatesAConflictViaGroupAssignmentsIsRejected() throws Exception {
        String merchantId = "990005";
        UUID typeA = insertGroupType();
        UUID typeB = insertGroupType();
        UUID groupG = insertGroup(typeA); // assigned to the draft rule
        UUID groupH = insertGroup(typeB); // already delivering the kind

        insertMembership(merchantId, groupG, typeA, PAST, null);
        insertMembership(merchantId, groupH, typeB, PAST, null);

        UUID ruleH = insertRule("MGT_I_05_H", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(ruleH, "MERCHANT_GROUP", groupH.toString(), true);

        // Draft rule with the same kind and an enabled group assignment (seeded directly, since the
        // service forbids assigning a non-active rule). Activation must detect the resulting conflict.
        UUID draftRule = insertRule("MGT_I_05_DRAFT", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "DRAFT", Set.of("SBP_C2B"));
        insertAssignment(draftRule, "MERCHANT_GROUP", groupG.toString(), true);

        mockMvc.perform(post("/internal/v1/limit-management/rules/{ruleId}/activate", draftRule))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LIMIT_KIND_CONFLICT"))
                .andExpect(jsonPath("$.error.conflicts[0].merchantId").value(merchantId))
                .andExpect(jsonPath("$.error.conflicts[0].limitKind.checkType").value("COUNT_DAY"))
                .andExpect(jsonPath("$.error.conflicts[0].existingGroupId").value(groupH.toString()))
                .andExpect(jsonPath("$.error.conflicts[0].requestedGroupId").value(groupG.toString()));
    }

    // ---- MGT-I-07: join a second group delivering a DISJOINT kind -> allowed ----

    @Test
    void addingMembershipWithADisjointKindIsAllowed() throws Exception {
        String merchantId = "990007";
        UUID typeA = insertGroupType();
        UUID typeB = insertGroupType();
        UUID groupG = insertGroup(typeA); // requested
        UUID groupH = insertGroup(typeB); // already delivering a different kind

        UUID ruleG = insertRule("MGT_I_07_G", RuleMetric.AMOUNT, RulePeriod.MONTH, LimitTargetType.ACCOUNT,
                OperationDirection.OUT, "ACTIVE", Set.of("SBP_B2B_OUT"));
        insertAssignment(ruleG, "MERCHANT_GROUP", groupG.toString(), true);
        UUID ruleH = insertRule("MGT_I_07_H", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(ruleH, "MERCHANT_GROUP", groupH.toString(), true);

        insertMembership(merchantId, groupH, typeB, PAST, null);

        mockMvc.perform(post("/internal/v1/limit-management/merchant-group-memberships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "merchantId": "%s", "groupId": "%s", "validFrom": "2025-01-01T00:00:00Z" }
                                """.formatted(merchantId, groupG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").value(merchantId))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    // ---- seed helpers (mirror PostgresLimitKindInvariantRepositoryIntegrationTest) ----

    private UUID insertGroupType() {
        UUID typeId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into limit_management.merchant_group_types
                    (id, code, name, description, enabled, sort_order, created_at, updated_at)
                values (?, ?, ?, null, true, 10, now(), now())
                """, typeId, "type-" + typeId, "Type " + typeId);
        return typeId;
    }

    private UUID insertGroup(UUID typeId) {
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into limit_management.merchant_groups
                    (id, type_id, code, name, description, enabled, created_at, updated_at)
                values (?, ?, ?, ?, null, true, now(), now())
                """, groupId, typeId, "group-" + groupId, "Group " + groupId);
        return groupId;
    }

    private void insertMembership(String merchantId, UUID groupId, UUID groupTypeId, Instant validFrom, Instant validTo) {
        jdbcTemplate.update("""
                insert into limit_management.merchant_group_memberships
                    (id, merchant_id, group_id, group_type_id, valid_from, valid_to, created_at, created_by, closed_at, closed_by)
                values (?, ?, ?, ?, ?, ?, now(), 'tester', null, null)
                """,
                UUID.randomUUID(), merchantId, groupId, groupTypeId,
                Timestamp.from(validFrom), validTo == null ? null : Timestamp.from(validTo));
    }

    private UUID insertRule(String code, RuleMetric metric, RulePeriod period, LimitTargetType targetType,
                             OperationDirection direction, String status, Set<String> operationTypes) {
        UUID ruleId = UUID.randomUUID();
        boolean interval = metric == RuleMetric.INTERVAL;
        boolean activated = !"DRAFT".equals(status);
        jdbcTemplate.update("""
                insert into limit_management.limit_rules
                    (id, code, version, name, direction,
                     attribute_selector_type, attribute_selector_value, target_type,
                     metric, period, aggregation_scope, currency, interval_minutes,
                     limit_value, error_message_template,
                     status, created_at, updated_at, activated_at, disabled_at)
                values (?, ?, 1, ?, ?,
                        'NONE', null, ?,
                        ?, ?, ?, ?, ?,
                        ?, 'template',
                        ?, now(), now(), ?, null)
                """,
                ruleId, code, code, direction.name(),
                targetType == null ? null : targetType.name(),
                metric.name(), period == null ? null : period.name(),
                interval ? "TARGET" : "OWNER",
                interval ? null : "RUB",
                interval ? 15 : null,
                interval ? null : BigDecimal.valueOf(1000),
                status,
                activated ? Timestamp.from(PAST) : null);
        for (String operationType : operationTypes) {
            jdbcTemplate.update("""
                    insert into limit_management.limit_rule_operation_type (rule_id, operation_type_code)
                    values (?, ?)
                    """, ruleId, operationType);
        }
        return ruleId;
    }

    private void insertAssignment(UUID ruleId, String ownerType, String ownerId, boolean enabled) {
        jdbcTemplate.update("""
                insert into limit_management.limit_assignments
                    (id, rule_id, owner_type, owner_id, limit_mode, valid_from, valid_to, enabled, created_at, updated_at)
                values (?, ?, ?, ?, 'UNLIMITED', ?, null, ?, now(), now())
                """, UUID.randomUUID(), ruleId, ownerType, ownerId, Timestamp.from(PAST), enabled);
    }
}
