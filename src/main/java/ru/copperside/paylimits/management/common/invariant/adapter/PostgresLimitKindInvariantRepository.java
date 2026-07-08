package ru.copperside.paylimits.management.common.invariant.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository;
import ru.copperside.paylimits.management.limitrule.domain.LimitKind;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
public class PostgresLimitKindInvariantRepository implements LimitKindInvariantRepository {

    /**
     * Distinct advisory-lock namespaces (first arg to the two-arg
     * {@code pg_advisory_xact_lock(int, int)} form) so merchant-keyed and rule-keyed locks live in
     * disjoint 32-bit keyspaces and cannot spuriously collide across domains.
     */
    private static final int MERCHANT_LOCK_NAMESPACE = 1;
    private static final int RULE_LOCK_NAMESPACE = 2;

    private final JdbcTemplate jdbcTemplate;

    public PostgresLimitKindInvariantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void lockMerchant(String merchantId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(?, hashtext(?))", (ResultSet rs) -> null,
                MERCHANT_LOCK_NAMESPACE, merchantId);
    }

    @Override
    public void lockRule(UUID ruleId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(?, hashtext(?))", (ResultSet rs) -> null,
                RULE_LOCK_NAMESPACE, ruleId.toString());
    }

    @Override
    public List<LimitKind> kindsDeliveredByGroup(UUID groupId, Instant at) {
        // Intentional INNER join here (vs. the LEFT join in kindOfRule below): a rule with zero
        // operation types can't be ACTIVE (validation 1-4), so an INNER join never silently drops a
        // deliverable kind for this query.
        //
        // The assignment's own validity window is filtered at `at`: an expired (or not-yet-effective)
        // assignment no longer delivers its kind, so it must not participate in the invariant scan.
        Timestamp instant = Timestamp.from(at);
        return jdbcTemplate.query("""
                select r.metric, r.period, r.target_type, r.direction,
                       array_agg(ot.operation_type_code) as operation_types
                from limit_management.limit_assignments a
                join limit_management.limit_rules r on r.id = a.rule_id
                join limit_management.limit_rule_operation_type ot on ot.rule_id = r.id
                where a.owner_type = 'MERCHANT_GROUP' and a.owner_id = ? and a.enabled = true
                  and a.valid_from <= ? and (a.valid_to is null or a.valid_to > ?)
                  and r.status = 'ACTIVE'
                group by r.id, r.metric, r.period, r.target_type, r.direction
                """, (rs, rowNum) -> mapKind(rs), groupId.toString(), instant, instant);
    }

    @Override
    public List<String> membersOfGroup(UUID groupId, Instant at) {
        return jdbcTemplate.queryForList("""
                select distinct merchant_id
                from limit_management.merchant_group_memberships
                where group_id = ? and (valid_to is null or valid_to > ?)
                """, String.class, groupId, Timestamp.from(at));
    }

    @Override
    public List<MerchantGroupKind> kindsReceivedByMerchantExcludingGroup(String merchantId, UUID excludedGroupId, Instant at) {
        return kindsReceivedByMerchantExcludingGroups(merchantId, List.of(excludedGroupId), at);
    }

    @Override
    public List<MerchantGroupKind> kindsReceivedByMerchantExcludingGroups(
            String merchantId, Collection<UUID> excludedGroupIds, Instant at) {
        List<UUID> excluded = excludedGroupIds == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(excludedGroupIds));
        // The `?` placeholders bind positionally in textual order: the two in the assignment join come
        // first, then the merchant id, then the membership window instant. Both the membership side
        // (m.valid_to) AND the assignment side (a.valid_from/a.valid_to) are filtered at `at` so an
        // expired-but-enabled assignment no longer counts as delivering its kind.
        Timestamp instant = Timestamp.from(at);
        StringBuilder sql = new StringBuilder("""
                select m.group_id as membership_group_id, r.metric, r.period, r.target_type, r.direction,
                       array_agg(ot.operation_type_code) as operation_types
                from limit_management.merchant_group_memberships m
                join limit_management.limit_assignments a
                    on a.owner_type = 'MERCHANT_GROUP' and a.owner_id = m.group_id::text and a.enabled = true
                    and a.valid_from <= ? and (a.valid_to is null or a.valid_to > ?)
                join limit_management.limit_rules r on r.id = a.rule_id and r.status = 'ACTIVE'
                join limit_management.limit_rule_operation_type ot on ot.rule_id = r.id
                where m.merchant_id = ?
                  and (m.valid_to is null or m.valid_to > ?)
                """);
        List<Object> params = new java.util.ArrayList<>();
        params.add(instant);
        params.add(instant);
        params.add(merchantId);
        params.add(instant);
        if (!excluded.isEmpty()) {
            String placeholders = excluded.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
            sql.append("  and m.group_id not in (").append(placeholders).append(")\n");
            params.addAll(excluded);
        }
        sql.append("group by m.group_id, r.id, r.metric, r.period, r.target_type, r.direction");
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new MerchantGroupKind(rs.getObject("membership_group_id", UUID.class), mapKind(rs)),
                params.toArray());
    }

    @Override
    public List<UUID> groupsWithEnabledAssignmentForRule(UUID ruleId, Instant at) {
        // Only assignments in effect at `at` participate: an expired (or not-yet-effective) assignment
        // no longer delivers the rule's kind and must not trigger a false activation conflict.
        Timestamp instant = Timestamp.from(at);
        return jdbcTemplate.query("""
                select distinct owner_id
                from limit_management.limit_assignments
                where owner_type = 'MERCHANT_GROUP' and rule_id = ? and enabled = true
                  and valid_from <= ? and (valid_to is null or valid_to > ?)
                """, (rs, rowNum) -> UUID.fromString(rs.getString("owner_id")), ruleId, instant, instant);
    }

    @Override
    public Optional<LimitKind> kindOfRule(UUID ruleId) {
        // LEFT join (vs. INNER above): must still return the rule's kind (with empty operation_types)
        // when called mid-save before its operation-type rows exist, rather than silently returning empty.
        return jdbcTemplate.query("""
                select r.metric, r.period, r.target_type, r.direction,
                       coalesce(
                           array_agg(ot.operation_type_code) filter (where ot.operation_type_code is not null),
                           array[]::varchar[]
                       ) as operation_types
                from limit_management.limit_rules r
                left join limit_management.limit_rule_operation_type ot on ot.rule_id = r.id
                where r.id = ?
                group by r.metric, r.period, r.target_type, r.direction
                """, (rs, rowNum) -> mapKind(rs), ruleId)
                .stream()
                .findFirst();
    }

    @Override
    public List<MemberOtherGroupKind> kindsReceivedByMembersOfGroup(UUID groupId, Instant at) {
        // Single round-trip for the whole group: m1 = current-or-future members of groupId, joined to
        // each such merchant's OTHER current-or-future memberships (m2, group_id <> groupId), joined to
        // that other group's enabled MERCHANT_GROUP assignments of ACTIVE rules whose own validity
        // window contains `at`. Both membership sides and the assignment window are filtered at `at`,
        // mirroring kindsReceivedByMerchantExcludingGroup(s).
        Timestamp instant = Timestamp.from(at);
        return jdbcTemplate.query("""
                select m1.merchant_id as merchant_id, m2.group_id as other_group_id,
                       r.metric, r.period, r.target_type, r.direction,
                       array_agg(ot.operation_type_code) as operation_types
                from limit_management.merchant_group_memberships m1
                join limit_management.merchant_group_memberships m2
                    on m2.merchant_id = m1.merchant_id
                    and m2.group_id <> m1.group_id
                    and (m2.valid_to is null or m2.valid_to > ?)
                join limit_management.limit_assignments a
                    on a.owner_type = 'MERCHANT_GROUP' and a.owner_id = m2.group_id::text and a.enabled = true
                    and a.valid_from <= ? and (a.valid_to is null or a.valid_to > ?)
                join limit_management.limit_rules r on r.id = a.rule_id and r.status = 'ACTIVE'
                join limit_management.limit_rule_operation_type ot on ot.rule_id = r.id
                where m1.group_id = ?
                  and (m1.valid_to is null or m1.valid_to > ?)
                group by m1.merchant_id, m2.group_id, r.id, r.metric, r.period, r.target_type, r.direction
                """,
                (rs, rowNum) -> new MemberOtherGroupKind(
                        rs.getString("merchant_id"), rs.getObject("other_group_id", UUID.class), mapKind(rs)),
                instant, instant, instant, groupId, instant);
    }

    private LimitKind mapKind(ResultSet rs) throws SQLException {
        String period = rs.getString("period");
        String targetType = rs.getString("target_type");
        return new LimitKind(
                RuleMetric.valueOf(rs.getString("metric")),
                period == null ? null : RulePeriod.valueOf(period),
                targetType == null ? null : LimitTargetType.valueOf(targetType),
                OperationDirection.valueOf(rs.getString("direction")),
                toStringSet(rs.getArray("operation_types")));
    }

    private Set<String> toStringSet(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return Set.of();
        }
        Object[] raw = (Object[]) sqlArray.getArray();
        Set<String> result = new LinkedHashSet<>();
        for (Object element : raw) {
            if (element != null) {
                result.add((String) element);
            }
        }
        return result;
    }
}
