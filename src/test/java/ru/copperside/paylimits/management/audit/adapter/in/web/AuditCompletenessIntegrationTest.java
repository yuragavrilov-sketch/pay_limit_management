package ru.copperside.paylimits.management.audit.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.audit.OperatorHeaderTestConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Structural audit-completeness guard (review-hardening task 1): drives EVERY mutating
 * {@code /internal/v1/limit-management/**} endpoint once, with a valid {@code X-Operator-Id}, against a
 * real Postgres and asserts an {@code audit_event} row with the correct {@code entity_type}/{@code action}
 * exists for each. If a future change adds a mutating endpoint (or drops the audit call from an existing
 * one), the expected (entity_type, action) pair goes unrecorded and this test fails — catching a silently
 * un-audited mutation that per-service tests would miss. Also pins the same-type membership tier move
 * emitting exactly two events (ASSIGN_MEMBERSHIP + CLOSE_MEMBERSHIP). Synthetic identifiers only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.context.annotation.Import(OperatorHeaderTestConfig.class)
class AuditCompletenessIntegrationTest {

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

    private static final String BASE = "/internal/v1/limit-management";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanAudit() {
        // Isolate the audit-event counts of this run; entity tables keep their Flyway-seeded state
        // (seeded rules are all DRAFT, so they never enter ACTIVE-only compilation).
        jdbcTemplate.update("delete from limit_management.audit_event");
    }

    @Test
    void everyMutatingEndpointWritesAnAuditEvent() throws Exception {
        String suffix = Long.toHexString(System.nanoTime());

        // ---- operation types ----
        UUID operationTypeId = idOf(perform(post(BASE + "/operation-types").content("""
                { "code": "AUDIT_OT_%1$s", "name": "Audit op type", "familyCode": "SBP",
                  "direction": "IN", "counterpartyType": "PHONE" }
                """.formatted(suffix))));
        perform(patch(BASE + "/operation-types/{id}", operationTypeId).content("""
                { "name": "Audit op type renamed" }
                """));

        // ---- rules (create/patch/activate/new-version/disable) ----
        String ruleCode = "AUDIT_RULE_" + suffix;
        UUID ruleId = idOf(perform(post(BASE + "/rules").content("""
                { "code": "%1$s", "name": "Audit rule", "operationTypes": ["AUDIT_OT_%2$s"],
                  "direction": "IN",
                  "measure": { "metric": "AMOUNT", "period": "DAY", "aggregationScope": "OWNER", "currency": "RUB" },
                  "limitValue": "1000.00", "errorMessageTemplate": "Limit exceeded" }
                """.formatted(ruleCode, suffix))));
        perform(patch(BASE + "/rules/{id}", ruleId).content("""
                { "name": "Audit rule renamed" }
                """));
        perform(post(BASE + "/rules/{id}/activate", ruleId).content("{}"));

        // ---- assignments (create on the now-active rule / patch / disable) ----
        UUID assignmentId = idOf(perform(post(BASE + "/assignments").content("""
                { "ruleId": "%s", "ownerType": "MERCHANT", "ownerId": "700100", "limitMode": "LIMITED",
                  "validFrom": "2026-01-01T00:00:00Z" }
                """.formatted(ruleId))));
        perform(patch(BASE + "/assignments/{id}", assignmentId).content("""
                { "limitMode": "UNLIMITED" }
                """));
        perform(post(BASE + "/assignments/{id}/disable", assignmentId).content("{}"));

        // ---- manifests (compile the active rule into both a rule manifest and a runtime manifest, then roll back) ----
        perform(post(BASE + "/rule-manifests").content("{}"));
        Instant effectiveFrom = Instant.now().plus(Duration.ofMinutes(10));
        UUID manifestId = idOf(perform(post(BASE + "/runtime-manifests").content("""
                { "effectiveFrom": "%s" }
                """.formatted(effectiveFrom))));
        Instant rollbackEffectiveFrom = Instant.now().plus(Duration.ofMinutes(20));
        perform(post(BASE + "/runtime-manifests/{id}/rollback", manifestId).content("""
                { "effectiveFrom": "%s" }
                """.formatted(rollbackEffectiveFrom)));

        // The rule is still ACTIVE here: version it (DRAFT v2) then disable v1.
        perform(post(BASE + "/rules/{id}/new-version", ruleId).content("{}"));
        perform(post(BASE + "/rules/{id}/disable", ruleId).content("{}"));

        // ---- merchant group types / groups ----
        UUID groupTypeId = idOf(perform(post(BASE + "/merchant-group-types").content("""
                { "code": "AUDIT_GT_%s", "name": "Audit group type", "sortOrder": 10 }
                """.formatted(suffix))));
        perform(patch(BASE + "/merchant-group-types/{id}", groupTypeId).content("""
                { "name": "Audit group type renamed", "enabled": true }
                """));
        UUID groupOneId = idOf(perform(post(BASE + "/merchant-groups").content("""
                { "typeId": "%s", "code": "AUDIT_G1_%s", "name": "Audit group 1" }
                """.formatted(groupTypeId, suffix))));
        perform(patch(BASE + "/merchant-groups/{id}", groupOneId).content("""
                { "name": "Audit group 1 renamed", "enabled": true }
                """));
        UUID groupTwoId = idOf(perform(post(BASE + "/merchant-groups").content("""
                { "typeId": "%s", "code": "AUDIT_G2_%s", "name": "Audit group 2" }
                """.formatted(groupTypeId, suffix))));

        // ---- memberships: assign to G1, then a same-type tier move to G2 (must emit ASSIGN + CLOSE) ----
        String merchantId = "700200";
        perform(post(BASE + "/merchant-group-memberships").content("""
                { "merchantId": "%s", "groupId": "%s", "validFrom": "2026-01-01T00:00:00Z" }
                """.formatted(merchantId, groupOneId)));

        int auditRowsBeforeTierMove = auditRowCount();
        int assignBeforeTierMove = actionCount("MERCHANT_GROUP_MEMBERSHIP", "ASSIGN_MEMBERSHIP");
        int closeBeforeTierMove = actionCount("MERCHANT_GROUP_MEMBERSHIP", "CLOSE_MEMBERSHIP");
        UUID tierMoveMembershipId = idOf(perform(post(BASE + "/merchant-group-memberships").content("""
                { "merchantId": "%s", "groupId": "%s", "validFrom": "2026-02-01T00:00:00Z" }
                """.formatted(merchantId, groupTwoId))));

        // The tier move must have appended exactly two audit rows: the CLOSE of the G1 predecessor and
        // the ASSIGN of the new G2 membership (spec §5, dual audit).
        assertThat(auditRowCount() - auditRowsBeforeTierMove).isEqualTo(2);
        assertThat(actionCount("MERCHANT_GROUP_MEMBERSHIP", "ASSIGN_MEMBERSHIP") - assignBeforeTierMove)
                .as("tier move appends one ASSIGN_MEMBERSHIP").isEqualTo(1);
        assertThat(actionCount("MERCHANT_GROUP_MEMBERSHIP", "CLOSE_MEMBERSHIP") - closeBeforeTierMove)
                .as("tier move appends one CLOSE_MEMBERSHIP").isEqualTo(1);

        // ---- explicit membership close endpoint ----
        perform(post(BASE + "/merchant-group-memberships/{id}/close", tierMoveMembershipId).content("""
                { "validTo": "2026-03-01T00:00:00Z" }
                """));

        // Every mutating endpoint must have left a matching (entity_type, action) audit row.
        List<String[]> expected = List.of(
                pair("OPERATION_TYPE", "CREATE"),
                pair("OPERATION_TYPE", "UPDATE"),
                pair("LIMIT_RULE", "CREATE"),
                pair("LIMIT_RULE", "UPDATE"),
                pair("LIMIT_RULE", "ACTIVATE"),
                pair("LIMIT_RULE", "DISABLE"),
                pair("LIMIT_RULE", "NEW_VERSION"),
                pair("LIMIT_ASSIGNMENT", "CREATE"),
                pair("LIMIT_ASSIGNMENT", "UPDATE"),
                pair("LIMIT_ASSIGNMENT", "DISABLE"),
                pair("MERCHANT_GROUP_TYPE", "CREATE"),
                pair("MERCHANT_GROUP_TYPE", "UPDATE"),
                pair("MERCHANT_GROUP", "CREATE"),
                pair("MERCHANT_GROUP", "UPDATE"),
                pair("MERCHANT_GROUP_MEMBERSHIP", "ASSIGN_MEMBERSHIP"),
                pair("MERCHANT_GROUP_MEMBERSHIP", "CLOSE_MEMBERSHIP"),
                pair("RULE_MANIFEST", "COMPILE"),
                pair("RUNTIME_MANIFEST", "COMPILE"),
                pair("RUNTIME_MANIFEST", "ROLLBACK"));

        for (String[] entry : expected) {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from limit_management.audit_event where entity_type = ? and action = ?",
                    Integer.class, entry[0], entry[1]);
            assertThat(count)
                    .as("audit_event for %s/%s", entry[0], entry[1])
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // ---- helpers ----

    private String perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private UUID idOf(String responseBody) throws Exception {
        JsonNode id = objectMapper.readTree(responseBody).at("/data/id");
        assertThat(id.isMissingNode() || id.isNull()).as("response carries /data/id").isFalse();
        return UUID.fromString(id.asText());
    }

    private int auditRowCount() {
        return jdbcTemplate.queryForObject("select count(*) from limit_management.audit_event", Integer.class);
    }

    private int actionCount(String entityType, String action) {
        return jdbcTemplate.queryForObject(
                "select count(*) from limit_management.audit_event where entity_type = ? and action = ?",
                Integer.class, entityType, action);
    }

    private static String[] pair(String entityType, String action) {
        return new String[]{entityType, action};
    }
}
