package ru.copperside.paylimits.management.common.invariant.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository;
import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository.MerchantGroupKind;
import ru.copperside.paylimits.management.limitrule.domain.LimitKind;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@Testcontainers
class PostgresLimitKindInvariantRepositoryIntegrationTest {

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

    private static final Instant NOW = Instant.parse("2026-07-07T09:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LimitKindInvariantRepository repository;

    @Test
    void kindsDeliveredByGroupCountsOnlyEnabledGroupAssignmentsOfActiveRules() {
        UUID typeId = insertGroupType();
        UUID groupId = insertGroup(typeId);

        UUID countedRule = insertRule("RULE_KIND_COUNTED", RuleMetric.COUNT, RulePeriod.DAY,
                LimitTargetType.PHONE, OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(countedRule, "MERCHANT_GROUP", groupId.toString(), true);

        // Excluded: draft rule, even with an enabled group assignment.
        UUID draftRule = insertRule("RULE_KIND_DRAFT", RuleMetric.COUNT, RulePeriod.WEEK,
                LimitTargetType.CARD, OperationDirection.IN, "DRAFT", Set.of("SBP_C2B"));
        insertAssignment(draftRule, "MERCHANT_GROUP", groupId.toString(), true);

        // Excluded: disabled assignment of an active rule.
        UUID disabledAssignmentRule = insertRule("RULE_KIND_DISABLED_ASSIGNMENT", RuleMetric.AMOUNT, RulePeriod.MONTH,
                LimitTargetType.ACCOUNT, OperationDirection.OUT, "ACTIVE", Set.of("SBP_B2C"));
        insertAssignment(disabledAssignmentRule, "MERCHANT_GROUP", groupId.toString(), false);

        // Excluded: merchant-level (not group) assignment of an active rule.
        UUID merchantOwnedRule = insertRule("RULE_KIND_MERCHANT_OWNED", RuleMetric.COUNT, RulePeriod.MONTH,
                LimitTargetType.PHONE, OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(merchantOwnedRule, "MERCHANT", "502118", true);

        List<LimitKind> kinds = repository.kindsDeliveredByGroup(groupId);

        assertThat(kinds).containsExactly(
                new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE, OperationDirection.IN, Set.of("SBP_C2B")));
    }

    @Test
    void membersOfGroupReturnsDistinctCurrentAndFutureMerchants() {
        UUID typeId = insertGroupType();
        UUID groupId = insertGroup(typeId);

        insertMembership("502111", groupId, typeId, NOW.minusSeconds(86400), null); // current, open-ended
        insertMembership("502112", groupId, typeId, NOW.plusSeconds(86400), null);  // future, open-ended
        insertMembership("502113", groupId, typeId, NOW.minusSeconds(10 * 86400), NOW.minusSeconds(5 * 86400)); // past, closed - excluded
        // Duplicate (closed, adjacent) membership for 502111 under a different id must not duplicate the result.
        insertMembership("502111", groupId, typeId, NOW.minusSeconds(2 * 86400), NOW.minusSeconds(86400));

        assertThat(repository.membersOfGroup(groupId, NOW)).containsExactlyInAnyOrder("502111", "502112");

        // The cutoff is the passed instant, not the database wall clock: querying at an earlier
        // instant (before 502113's valid_to) must include the membership that NOW excludes.
        assertThat(repository.membersOfGroup(groupId, NOW.minusSeconds(7 * 86400))).contains("502113");
    }

    @Test
    void kindsReceivedByMerchantExcludingGroupSkipsTheExcludedGroup() {
        // Three distinct group types: a merchant can only belong to one group of a given type
        // at a time (merchant_group_memberships_no_overlap), so concurrent memberships in this
        // test each need their own type.
        UUID excludedType = insertGroupType();
        UUID includedType = insertGroupType();
        UUID futureType = insertGroupType();
        UUID excludedGroup = insertGroup(excludedType);
        UUID includedGroup = insertGroup(includedType);
        UUID futureGroup = insertGroup(futureType);
        String merchantId = "502120";

        UUID excludedRule = insertRule("RULE_EXCLUDED_GROUP", RuleMetric.COUNT, RulePeriod.DAY,
                LimitTargetType.PHONE, OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(excludedRule, "MERCHANT_GROUP", excludedGroup.toString(), true);

        UUID includedRule = insertRule("RULE_INCLUDED_GROUP", RuleMetric.AMOUNT, RulePeriod.MONTH,
                LimitTargetType.ACCOUNT, OperationDirection.OUT, "ACTIVE", Set.of("SBP_B2C"));
        insertAssignment(includedRule, "MERCHANT_GROUP", includedGroup.toString(), true);

        UUID futureRule = insertRule("RULE_FUTURE_GROUP", RuleMetric.COUNT, RulePeriod.WEEK,
                LimitTargetType.CARD, OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(futureRule, "MERCHANT_GROUP", futureGroup.toString(), true);

        insertMembership(merchantId, excludedGroup, excludedType, NOW.minusSeconds(86400), null);
        insertMembership(merchantId, includedGroup, includedType, NOW.minusSeconds(86400), null);
        insertMembership(merchantId, futureGroup, futureType, NOW.plusSeconds(86400), null); // future membership still counts

        List<MerchantGroupKind> received = repository.kindsReceivedByMerchantExcludingGroup(merchantId, excludedGroup, NOW);

        assertThat(received).containsExactlyInAnyOrder(
                new MerchantGroupKind(includedGroup,
                        new LimitKind(RuleMetric.AMOUNT, RulePeriod.MONTH, LimitTargetType.ACCOUNT, OperationDirection.OUT, Set.of("SBP_B2C"))),
                new MerchantGroupKind(futureGroup,
                        new LimitKind(RuleMetric.COUNT, RulePeriod.WEEK, LimitTargetType.CARD, OperationDirection.IN, Set.of("SBP_C2B"))));
    }

    @Test
    void groupsWithEnabledAssignmentForRuleFindsOnlyEnabledGroupOwners() {
        UUID typeId = insertGroupType();
        UUID enabledGroup = insertGroup(typeId);
        UUID disabledGroup = insertGroup(typeId);

        UUID rule = insertRule("RULE_ASSIGNMENT_GROUPS", RuleMetric.COUNT, RulePeriod.DAY,
                LimitTargetType.PHONE, OperationDirection.IN, "ACTIVE", Set.of("SBP_C2B"));
        insertAssignment(rule, "MERCHANT_GROUP", enabledGroup.toString(), true);
        insertAssignment(rule, "MERCHANT_GROUP", disabledGroup.toString(), false);
        insertAssignment(rule, "MERCHANT", "502118", true);

        assertThat(repository.groupsWithEnabledAssignmentForRule(rule)).containsExactly(enabledGroup);
    }

    @Test
    void kindOfRuleReturnsKindRegardlessOfStatusAndEmptyWhenMissing() {
        UUID draftRule = insertRule("RULE_KIND_OF_RULE", RuleMetric.INTERVAL, null,
                LimitTargetType.PHONE, OperationDirection.OUT, "DRAFT", Set.of("SBP_B2C", "SBP_C2B"));

        assertThat(repository.kindOfRule(draftRule)).contains(
                new LimitKind(RuleMetric.INTERVAL, null, LimitTargetType.PHONE, OperationDirection.OUT,
                        Set.of("SBP_B2C", "SBP_C2B")));
        assertThat(repository.kindOfRule(UUID.randomUUID())).isEmpty();
    }

    @Test
    void advisoryLocksDoNotErrorInsideATransaction() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatCode(() -> transactionTemplate.executeWithoutResult(status -> {
            repository.lockMerchant("502118");
            repository.lockRule(UUID.randomUUID());
        })).doesNotThrowAnyException();
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
                values (?, ?, ?, ?, ?, ?, ?, 'tester', null, null)
                """,
                UUID.randomUUID(), merchantId, groupId, groupTypeId,
                Timestamp.from(validFrom), validTo == null ? null : Timestamp.from(validTo),
                Timestamp.from(NOW));
    }

    private UUID insertRule(String code, RuleMetric metric, RulePeriod period, LimitTargetType targetType,
                             OperationDirection direction, String status, Set<String> operationTypes) {
        UUID ruleId = UUID.randomUUID();
        boolean interval = metric == RuleMetric.INTERVAL;
        boolean activated = !"DRAFT".equals(status);
        boolean disabled = "DISABLED".equals(status);
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
                        ?, now(), now(), ?, ?)
                """,
                ruleId, code, code, direction.name(),
                targetType == null ? null : targetType.name(),
                metric.name(), period == null ? null : period.name(),
                interval ? "TARGET" : "OWNER",
                interval ? null : "RUB",
                interval ? 15 : null,
                interval ? null : BigDecimal.valueOf(1000),
                status,
                activated ? Timestamp.from(NOW) : null,
                disabled ? Timestamp.from(NOW.plusSeconds(60)) : null);
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
                values (?, ?, ?, ?, 'UNLIMITED', ?, null, ?, ?, ?)
                """, UUID.randomUUID(), ruleId, ownerType, ownerId, Timestamp.from(NOW.minusSeconds(86400)), enabled,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
