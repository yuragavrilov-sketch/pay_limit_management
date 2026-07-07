package ru.copperside.paylimits.management.effectivelimits.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.audit.OperatorHeaderTestConfig;
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
 * MGT-U-08 integration leg (spec §3.5/§8): GET the effective-limits preview against a real
 * Postgres-backed context and assert the wire shape — level priority (MERCHANT > MERCHANT_GROUP >
 * GLOBAL), nested {@code overrides}, string {@code limitValue}, and {@code manifestVersion} tracking
 * the latest compiled manifest. Synthetic merchant/group/rule identifiers only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(OperatorHeaderTestConfig.class)
class EffectiveLimitsControllerIntegrationTest {

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
    private static final Instant AT = Instant.parse("2026-07-06T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // GLOBAL-level assignments apply to every merchant, so leftover rows from one test method would
    // leak into another's result set; each test starts from a clean slate (FK-respecting order).
    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from limit_management.runtime_manifest_memberships");
        jdbcTemplate.update("delete from limit_management.runtime_manifest_assignments");
        jdbcTemplate.update("delete from limit_management.runtime_manifest_rules");
        jdbcTemplate.update("delete from limit_management.runtime_manifests");
        jdbcTemplate.update("delete from limit_management.limit_assignments");
        jdbcTemplate.update("delete from limit_management.limit_rules");
        jdbcTemplate.update("delete from limit_management.merchant_group_memberships");
        jdbcTemplate.update("delete from limit_management.merchant_groups");
        jdbcTemplate.update("delete from limit_management.merchant_group_types");
    }

    @Test
    void resolvesMostSpecificLevelPerKindAndReportsOverridesAndLatestManifestVersion() throws Exception {
        String merchantId = "EFF-42";
        UUID groupTypeId = insertGroupType();
        UUID groupId = insertGroup(groupTypeId);
        insertMembership(merchantId, groupId, groupTypeId, PAST, null);

        UUID ruleGlobal = insertRule("EFF_GLOBAL_RULE", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT"), new BigDecimal("100.00"));
        UUID ruleGroup = insertRule("EFF_GROUP_RULE", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT"), new BigDecimal("30.00"));
        UUID ruleMerchant = insertRule("EFF_MERCHANT_RULE", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD,
                OperationDirection.OUT, Set.of("OCT"), new BigDecimal("10.00"));

        insertAssignment(ruleGlobal, "GLOBAL", null, "LIMITED");
        insertAssignment(ruleGroup, "MERCHANT_GROUP", groupId.toString(), "LIMITED");
        UUID merchantAssignmentId = insertAssignment(ruleMerchant, "MERCHANT", merchantId, "LIMITED");

        // No manifest compiled yet -> manifestVersion is null.
        mockMvc.perform(get("/internal/v1/limit-management/merchants/{merchantId}/effective-limits", merchantId)
                        .param("at", AT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").value(merchantId))
                .andExpect(jsonPath("$.data.manifestVersion").doesNotExist())
                .andExpect(jsonPath("$.data.limits.length()").value(1))
                .andExpect(jsonPath("$.data.limits[0].ruleCode").value("EFF_MERCHANT_RULE"))
                .andExpect(jsonPath("$.data.limits[0].limitType").value("COUNT_DAY"))
                .andExpect(jsonPath("$.data.limits[0].targetType").value("CARD"))
                .andExpect(jsonPath("$.data.limits[0].direction").value("OUT"))
                .andExpect(jsonPath("$.data.limits[0].operationTypes[0]").value("OCT"))
                .andExpect(jsonPath("$.data.limits[0].appliedLevel").value("MERCHANT"))
                .andExpect(jsonPath("$.data.limits[0].ownerId").value(merchantId))
                .andExpect(jsonPath("$.data.limits[0].mode").value("LIMITED"))
                .andExpect(jsonPath("$.data.limits[0].limitValue").value("10.00"))
                .andExpect(jsonPath("$.data.limits[0].assignmentId").value(merchantAssignmentId.toString()))
                .andExpect(jsonPath("$.data.limits[0].overrides.length()").value(2))
                .andExpect(jsonPath("$.data.limits[0].overrides[0].level").value("MERCHANT_GROUP"))
                .andExpect(jsonPath("$.data.limits[0].overrides[0].ownerId").value(groupId.toString()))
                .andExpect(jsonPath("$.data.limits[0].overrides[0].limitValue").value("30.00"))
                .andExpect(jsonPath("$.data.limits[0].overrides[1].level").value("GLOBAL"))
                .andExpect(jsonPath("$.data.limits[0].overrides[1].ownerId").doesNotExist())
                .andExpect(jsonPath("$.data.limits[0].overrides[1].limitValue").value("100.00"));

        // Compile a manifest -> its version now shows up in the preview.
        Instant effectiveFrom = Instant.now().plus(Duration.ofMinutes(10));
        String manifestBody = mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"effectiveFrom\": \"%s\" }".formatted(effectiveFrom)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int manifestVersion = objectMapper.readTree(manifestBody).at("/data/document/manifestVersion").asInt();

        mockMvc.perform(get("/internal/v1/limit-management/merchants/{merchantId}/effective-limits", merchantId)
                        .param("at", AT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manifestVersion").value(manifestVersion));
    }

    @Test
    void unlimitedAtMerchantLevelIsReportedAsNotEnforcedAndGlobalOnlyKindAppliesGlobal() throws Exception {
        String merchantId = "EFF-77";

        // Kind 1: GROUP delivers a numeric limit, MERCHANT overrides it with UNLIMITED.
        UUID groupTypeId = insertGroupType();
        UUID groupId = insertGroup(groupTypeId);
        insertMembership(merchantId, groupId, groupTypeId, PAST, null);
        UUID ruleGroup = insertRule("EFF_UNLIM_GROUP_RULE", RuleMetric.AMOUNT, RulePeriod.DAY, LimitTargetType.ACCOUNT,
                OperationDirection.OUT, Set.of("SBP_B2B_OUT"), new BigDecimal("30000.00"));
        UUID ruleMerchant = insertRule("EFF_UNLIM_MERCHANT_RULE", RuleMetric.AMOUNT, RulePeriod.DAY, LimitTargetType.ACCOUNT,
                OperationDirection.OUT, Set.of("SBP_B2B_OUT"), new BigDecimal("30000.00"));
        insertAssignment(ruleGroup, "MERCHANT_GROUP", groupId.toString(), "LIMITED");
        UUID merchantAssignmentId = insertAssignment(ruleMerchant, "MERCHANT", merchantId, "UNLIMITED");

        // Kind 2: GLOBAL-only kind, no group/merchant override -> appliedLevel = GLOBAL.
        UUID ruleGlobal = insertRule("EFF_GLOBAL_ONLY_RULE", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, Set.of("SBP_C2B"), new BigDecimal("5.00"));
        insertAssignment(ruleGlobal, "GLOBAL", null, "LIMITED");

        String body = mockMvc.perform(get("/internal/v1/limit-management/merchants/{merchantId}/effective-limits", merchantId)
                        .param("at", AT.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode limits = objectMapper.readTree(body).at("/data/limits");
        assertThat(limits).hasSize(2);

        JsonNode unlimited = findByField(limits, "ruleCode", "EFF_UNLIM_MERCHANT_RULE");
        assertThat(unlimited).isNotNull();
        assertThat(unlimited.get("appliedLevel").asText()).isEqualTo("MERCHANT");
        assertThat(unlimited.get("mode").asText()).isEqualTo("UNLIMITED");
        assertThat(unlimited.get("limitValue").isNull()).isTrue();
        assertThat(unlimited.get("assignmentId").asText()).isEqualTo(merchantAssignmentId.toString());
        assertThat(unlimited.get("overrides")).hasSize(1);
        assertThat(unlimited.get("overrides").get(0).get("level").asText()).isEqualTo("MERCHANT_GROUP");
        assertThat(unlimited.get("overrides").get(0).get("limitValue").asText()).isEqualTo("30000.00");

        JsonNode globalOnly = findByField(limits, "ruleCode", "EFF_GLOBAL_ONLY_RULE");
        assertThat(globalOnly).isNotNull();
        assertThat(globalOnly.get("appliedLevel").asText()).isEqualTo("GLOBAL");
        assertThat(globalOnly.get("ownerId").isNull()).isTrue();
        assertThat(globalOnly.get("limitValue").asText()).isEqualTo("5.00");
        assertThat(globalOnly.get("overrides")).isEmpty();
    }

    // ---- seed helpers (mirror GlobalAssignmentManifestIntegrationTest) ----

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
                             OperationDirection direction, Set<String> operationTypes, BigDecimal limitValue) {
        UUID ruleId = UUID.randomUUID();
        boolean interval = metric == RuleMetric.INTERVAL;
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
                        'ACTIVE', now(), now(), now(), null)
                """,
                ruleId, code, code, direction.name(),
                targetType == null ? null : targetType.name(),
                metric.name(), period == null ? null : period.name(),
                (interval || targetType != null) ? "TARGET" : "OWNER",
                interval ? null : "RUB",
                interval ? 15 : null,
                interval ? null : limitValue);
        for (String operationType : operationTypes) {
            jdbcTemplate.update("""
                    insert into limit_management.limit_rule_operation_type (rule_id, operation_type_code)
                    values (?, ?)
                    """, ruleId, operationType);
        }
        return ruleId;
    }

    private UUID insertAssignment(UUID ruleId, String ownerType, String ownerId, String limitMode) {
        UUID assignmentId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into limit_management.limit_assignments
                    (id, rule_id, owner_type, owner_id, limit_mode, valid_from, valid_to, enabled, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, null, true, now(), now())
                """, assignmentId, ruleId, ownerType, ownerId, limitMode, Timestamp.from(PAST));
        return assignmentId;
    }
}
