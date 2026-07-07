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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
public class PostgresLimitKindInvariantRepository implements LimitKindInvariantRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresLimitKindInvariantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void lockMerchant(String merchantId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtext(?))", (ResultSet rs) -> null, merchantId);
    }

    @Override
    public void lockRule(UUID ruleId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtext(?))", (ResultSet rs) -> null, ruleId.toString());
    }

    @Override
    public List<LimitKind> kindsDeliveredByGroup(UUID groupId) {
        return jdbcTemplate.query("""
                select r.metric, r.period, r.target_type, r.direction,
                       array_agg(ot.operation_type_code) as operation_types
                from limit_management.limit_assignments a
                join limit_management.limit_rules r on r.id = a.rule_id
                join limit_management.limit_rule_operation_type ot on ot.rule_id = r.id
                where a.owner_type = 'MERCHANT_GROUP' and a.owner_id = ? and a.enabled = true
                  and r.status = 'ACTIVE'
                group by r.id, r.metric, r.period, r.target_type, r.direction
                """, (rs, rowNum) -> mapKind(rs), groupId.toString());
    }

    @Override
    public List<String> membersOfGroup(UUID groupId) {
        return jdbcTemplate.queryForList("""
                select distinct merchant_id
                from limit_management.merchant_group_memberships
                where group_id = ? and (valid_to is null or valid_to > now())
                """, String.class, groupId);
    }

    @Override
    public List<MerchantGroupKind> kindsReceivedByMerchantExcludingGroup(String merchantId, UUID excludedGroupId) {
        return jdbcTemplate.query("""
                select m.group_id as membership_group_id, r.metric, r.period, r.target_type, r.direction,
                       array_agg(ot.operation_type_code) as operation_types
                from limit_management.merchant_group_memberships m
                join limit_management.limit_assignments a
                    on a.owner_type = 'MERCHANT_GROUP' and a.owner_id = m.group_id::text and a.enabled = true
                join limit_management.limit_rules r on r.id = a.rule_id and r.status = 'ACTIVE'
                join limit_management.limit_rule_operation_type ot on ot.rule_id = r.id
                where m.merchant_id = ?
                  and (m.valid_to is null or m.valid_to > now())
                  and m.group_id <> ?
                group by m.group_id, r.id, r.metric, r.period, r.target_type, r.direction
                """,
                (rs, rowNum) -> new MerchantGroupKind(rs.getObject("membership_group_id", UUID.class), mapKind(rs)),
                merchantId, excludedGroupId);
    }

    @Override
    public List<UUID> groupsWithEnabledAssignmentForRule(UUID ruleId) {
        return jdbcTemplate.query("""
                select distinct owner_id
                from limit_management.limit_assignments
                where owner_type = 'MERCHANT_GROUP' and rule_id = ? and enabled = true
                """, (rs, rowNum) -> UUID.fromString(rs.getString("owner_id")), ruleId);
    }

    @Override
    public Optional<LimitKind> kindOfRule(UUID ruleId) {
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
