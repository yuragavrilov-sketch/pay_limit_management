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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Last-line-of-defence and concurrency coverage for the limit-kind non-overlap invariant (spec §3.4,
 * cases MGT-I-06 and MGT-I-09). Boots the real Postgres-backed context so the advisory lock and the
 * connection pool are exercised for real; synthetic merchant identifiers only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.context.annotation.Import(ru.copperside.paylimits.management.audit.OperatorHeaderTestConfig.class)
class LimitKindInvariantConcurrencyIntegrationTest {

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

    @org.junit.jupiter.api.BeforeEach
    void cleanDatabase() {
        // Isolate each test: several cases here assert GLOBAL counts (persisted manifests, membership
        // rows), so shared container state between methods must be reset.
        jdbcTemplate.execute("""
                truncate table
                    limit_management.runtime_manifests,
                    limit_management.merchant_group_memberships,
                    limit_management.limit_assignments,
                    limit_management.limit_rule_operation_type,
                    limit_management.limit_rules,
                    limit_management.merchant_groups,
                    limit_management.merchant_group_types
                restart identity cascade
                """);
    }

    // ---- MGT-I-06: two individually-valid group assignments of the SAME rule race; the advisory
    // lock (keyed by rule_id) serializes them, so exactly one commits and the other gets 409. ----

    @Test
    void concurrentAssignmentsOfTheSameRuleToTwoSharedGroupsSerializeSoOneWins() throws Exception {
        String merchantId = "990006";
        UUID typeA = insertGroupType();
        UUID typeB = insertGroupType();
        UUID groupG = insertGroup(typeA);
        UUID groupH = insertGroup(typeB);

        // The merchant belongs to both groups; either assignment alone is valid, but delivering the
        // same kind from both groups violates the invariant.
        insertMembership(merchantId, groupG, typeA);
        insertMembership(merchantId, groupH, typeB);

        UUID rule = insertRule("MGT_I_06", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Integer> first = pool.submit(() -> assignRuleToGroup(ready, go, rule, groupG));
            Future<Integer> second = pool.submit(() -> assignRuleToGroup(ready, go, rule, groupH));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int firstStatus = first.get(30, TimeUnit.SECONDS);
            int secondStatus = second.get(30, TimeUnit.SECONDS);

            // Exactly one 200 (winner) and one 409 (loser) — the advisory lock serialized them.
            assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        // The database ends with a single enabled MERCHANT_GROUP assignment of the rule.
        Integer enabled = jdbcTemplate.queryForObject("""
                select count(*) from limit_management.limit_assignments
                where rule_id = ? and owner_type = 'MERCHANT_GROUP' and enabled = true
                """, Integer.class, rule);
        assertThat(enabled).isEqualTo(1);
    }

    private int assignRuleToGroup(CountDownLatch ready, CountDownLatch go, UUID ruleId, UUID groupId) throws Exception {
        ready.countDown();
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("start signal not received");
        }
        return mockMvc.perform(post("/internal/v1/limit-management/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ruleId": "%s", "ownerType": "MERCHANT_GROUP", "ownerId": "%s",
                                  "limitMode": "UNLIMITED", "validFrom": "2025-01-01T00:00:00Z" }
                                """.formatted(ruleId, groupId)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    // ---- MGT-I-09: a snapshot seeded (around the API) into a conflicting state must be rejected at
    // manifest compilation with 422, and no manifest is persisted. ----

    @Test
    void compilingAConflictingSnapshotIsRejectedWith422AndPersistsNothing() throws Exception {
        String merchantId = "990009";
        UUID typeA = insertGroupType();
        UUID typeB = insertGroupType();
        UUID groupG = insertGroup(typeA);
        UUID groupH = insertGroup(typeB);

        insertMembership(merchantId, groupG, typeA);
        insertMembership(merchantId, groupH, typeB);

        // Two active rules of the SAME kind, each assigned to a different one of the merchant's groups.
        // This is only reachable by bypassing the interactive checks (seeded directly).
        UUID ruleG = insertRule("MGT_I_09_G", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(ruleG, groupG.toString());
        UUID ruleH = insertRule("MGT_I_09_H", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(ruleH, groupH.toString());

        String effectiveFrom = Instant.now().plus(Duration.ofHours(1)).toString();

        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"effectiveFrom\": \"%s\" }".formatted(effectiveFrom)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.error.code").value("LIMIT_KIND_CONFLICT"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.error.conflicts[0].merchantId").value(merchantId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.error.conflicts[0].limitKind.checkType").value("COUNT_DAY"));

        Integer manifests = jdbcTemplate.queryForObject(
                "select count(*) from limit_management.runtime_manifests", Integer.class);
        assertThat(manifests).isZero();
    }

    // ---- Defect #1 regression: a CLOSED membership must not contribute to the compile-time re-check.
    // A merchant that once belonged to a K-delivering group and now belongs to another K-delivering
    // group is NOT a live overlap, so compilation must SUCCEED and persist the manifest. ----

    @Test
    void compilingSucceedsWhenAClosedMembershipDeliversAKindAlsoDeliveredByALiveGroup() throws Exception {
        String merchantId = "990011";
        UUID typeA = insertGroupType();
        UUID typeB = insertGroupType();
        UUID closedGroup = insertGroup(typeA);
        UUID liveGroup = insertGroup(typeB);

        // Closed membership in closedGroup (valid_to in the past) + open-ended membership in liveGroup.
        insertClosedMembership(merchantId, closedGroup, typeA, PAST, Instant.parse("2021-01-01T00:00:00Z"));
        insertMembership(merchantId, liveGroup, typeB);

        UUID ruleClosed = insertRule("MGT_CLOSED", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(ruleClosed, closedGroup.toString());
        UUID ruleLive = insertRule("MGT_LIVE", RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE,
                OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(ruleLive, liveGroup.toString());

        String effectiveFrom = Instant.now().plus(Duration.ofHours(1)).toString();

        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"effectiveFrom\": \"%s\" }".formatted(effectiveFrom)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.status").value("VALID"))
                // The manifest payload still carries BOTH membership rows (closed one included).
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.membershipCount").value(2));

        Integer manifests = jdbcTemplate.queryForObject(
                "select count(*) from limit_management.runtime_manifests", Integer.class);
        assertThat(manifests).isEqualTo(1);
    }

    private void insertClosedMembership(String merchantId, UUID groupId, UUID groupTypeId, Instant validFrom, Instant validTo) {
        jdbcTemplate.update("""
                insert into limit_management.merchant_group_memberships
                    (id, merchant_id, group_id, group_type_id, valid_from, valid_to, created_at, created_by, closed_at, closed_by)
                values (?, ?, ?, ?, ?, ?, now(), 'tester', now(), 'tester')
                """,
                UUID.randomUUID(), merchantId, groupId, groupTypeId,
                Timestamp.from(validFrom), Timestamp.from(validTo));
    }

    // ---- seed helpers (mirror LimitKindInvariantIntegrationTest) ----

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

    private void insertMembership(String merchantId, UUID groupId, UUID groupTypeId) {
        jdbcTemplate.update("""
                insert into limit_management.merchant_group_memberships
                    (id, merchant_id, group_id, group_type_id, valid_from, valid_to, created_at, created_by, closed_at, closed_by)
                values (?, ?, ?, ?, ?, null, now(), 'tester', null, null)
                """,
                UUID.randomUUID(), merchantId, groupId, groupTypeId, Timestamp.from(PAST));
    }

    private UUID insertRule(String code, RuleMetric metric, RulePeriod period, LimitTargetType targetType,
                            OperationDirection direction, String status, Set<String> operationTypes) {
        UUID ruleId = UUID.randomUUID();
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
                        ?, ?, ?, 'RUB', null,
                        ?, 'template',
                        ?, now(), now(), ?, null)
                """,
                ruleId, code, code, direction.name(),
                targetType == null ? null : targetType.name(),
                metric.name(), period == null ? null : period.name(),
                // Scope must be TARGET whenever a targetType is carried (V13 DB check); every fixture
                // here passes a non-null targetType.
                targetType != null ? "TARGET" : "OWNER",
                BigDecimal.valueOf(1000),
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

    private void insertAssignment(UUID ruleId, String ownerId) {
        jdbcTemplate.update("""
                insert into limit_management.limit_assignments
                    (id, rule_id, owner_type, owner_id, limit_mode, valid_from, valid_to, enabled, created_at, updated_at)
                values (?, ?, 'MERCHANT_GROUP', ?, 'UNLIMITED', ?, null, true, now(), now())
                """, UUID.randomUUID(), ruleId, ownerId, Timestamp.from(PAST));
    }
}
