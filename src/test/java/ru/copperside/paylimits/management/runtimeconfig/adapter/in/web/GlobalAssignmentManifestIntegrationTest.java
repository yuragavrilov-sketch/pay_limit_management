package ru.copperside.paylimits.management.runtimeconfig.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MGT-I-17 (spec §8): create a GLOBAL assignment, confirm it is listed, and confirm it flows into a
 * compiled runtime manifest. Also covers the stage-2 DoD assertion that GLOBAL assignments are exempt
 * from the limit-kind non-overlap invariant (spec §2.3/§3.4). Boots the real Postgres-backed context
 * and drives the REST endpoints through MockMvc; synthetic rule/merchant identifiers only, no
 * PAN/phone/account values.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.context.annotation.Import(ru.copperside.paylimits.management.audit.OperatorHeaderTestConfig.class)
class GlobalAssignmentManifestIntegrationTest {

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

    private static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---- MGT-I-17: create GLOBAL -> list -> included in compiled manifest ----

    @Test
    void createsGlobalAssignmentFiltersInListAndIsIncludedInCompiledManifest() throws Exception {
        UUID ruleId = insertRule("MGT_I_17_RULE", RuleMetric.AMOUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));

        // Step 1: POST /assignments GLOBAL (no ownerId) -> success, ownerId absent/null.
        String createBody = mockMvc.perform(post("/internal/v1/limit-management/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ruleId": "%s", "ownerType": "GLOBAL", "limitMode": "LIMITED",
                                  "validFrom": "2020-01-01T00:00:00Z" }
                                """.formatted(ruleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerType").value("GLOBAL"))
                .andExpect(jsonPath("$.data.ownerId").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        UUID assignmentId = UUID.fromString(objectMapper.readTree(createBody).at("/data/id").asText());

        // Step 2: GET /assignments -> list contains the GLOBAL assignment.
        String listBody = mockMvc.perform(get("/internal/v1/limit-management/assignments"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode listed = findByField(objectMapper.readTree(listBody).at("/data"), "id", assignmentId.toString());
        assertThat(listed).isNotNull();
        assertThat(listed.get("ownerType").asText()).isEqualTo("GLOBAL");
        assertThat(listed.get("ownerId").isNull()).isTrue();

        // Step 3: compile a manifest -> the GLOBAL assignment is included.
        Instant effectiveFrom = Instant.now().plus(Duration.ofMinutes(10));
        String manifestBody = mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "effectiveFrom": "%s" }
                                """.formatted(effectiveFrom)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode compiled = findByField(
                objectMapper.readTree(manifestBody).at("/data/document/assignments"), "assignmentId", assignmentId.toString());
        assertThat(compiled).isNotNull();
        assertThat(compiled.get("owner").get("level").asText()).isEqualTo("GLOBAL");
        assertThat(compiled.get("owner").get("id").isNull()).isTrue();
        assertThat(compiled.get("ruleId").asText()).isEqualTo(ruleId.toString());
    }

    // ---- Stage-2 DoD: GLOBAL is exempt from the limit-kind non-overlap invariant ----

    @Test
    void globalAssignmentSucceedsEvenWhenTheSameKindWouldConflictAtGroupLevel() throws Exception {
        String merchantId = "990017";
        UUID typeA = insertGroupType();
        UUID typeB = insertGroupType();
        UUID groupG = insertGroup(typeA); // would be the conflicting assignment target at group level
        UUID groupH = insertGroup(typeB); // already delivering the kind to the merchant

        insertMembership(merchantId, groupG, typeA, PAST, null);
        insertMembership(merchantId, groupH, typeB, PAST, null);

        UUID ruleH = insertRule("MGT_I_17_H", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(ruleH, "MERCHANT_GROUP", groupH.toString(), true);

        // Same kind (metric/period/target/direction/operationTypes) as ruleH. Assigning this to groupG
        // via MERCHANT_GROUP would 409 (mirrors LimitKindInvariantIntegrationTest MGT-I-04): the shared
        // merchant would receive the same kind from two different groups. Assigning it GLOBAL instead
        // must succeed — GLOBAL assignments never reach the invariant checker.
        UUID ruleG = insertRule("MGT_I_17_G", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));

        mockMvc.perform(post("/internal/v1/limit-management/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ruleId": "%s", "ownerType": "GLOBAL", "limitMode": "UNLIMITED",
                                  "validFrom": "2025-01-01T00:00:00Z" }
                                """.formatted(ruleG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerType").value("GLOBAL"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    // ---- seed helpers (mirror LimitKindInvariantIntegrationTest) ----

    private JsonNode findByField(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            JsonNode fieldNode = node.get(field);
            if (fieldNode != null && !fieldNode.isNull() && value.equals(fieldNode.asText())) {
                return node;
            }
        }
        return null;
    }

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
                // Scope must be TARGET whenever a targetType is carried (V13 DB check).
                (interval || targetType != null) ? "TARGET" : "OWNER",
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
