package ru.copperside.paylimits.management.effectivelimits.adapter.out.postgres;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import ru.copperside.paylimits.management.effectivelimits.application.port.out.EffectiveLimitsRepository;
import ru.copperside.paylimits.management.effectivelimits.domain.EffectiveLimitCandidate;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
public class PostgresEffectiveLimitsRepository implements EffectiveLimitsRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresEffectiveLimitsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<EffectiveLimitCandidate> findCandidateAssignments(String merchantId, Instant at) {
        Timestamp ts = Timestamp.from(at);
        // GLOBAL assignments always apply; MERCHANT applies when owner_id is this merchant; MERCHANT_GROUP
        // applies when owner_id is a group the merchant is an active member of at `at` (period contains `at`).
        List<EffectiveLimitCandidate> rows = jdbcTemplate.query("""
                select a.id as assignment_id, a.owner_type, a.owner_id, a.limit_mode,
                       r.id as rule_id, r.code, r.version, r.direction, r.metric, r.period,
                       r.target_type, r.limit_value
                from limit_management.limit_assignments a
                join limit_management.limit_rules r on r.id = a.rule_id
                where a.enabled = true
                  and r.status = 'ACTIVE'
                  and a.valid_from <= ?
                  and (a.valid_to is null or a.valid_to > ?)
                  and (
                    a.owner_type = 'GLOBAL'
                    or (a.owner_type = 'MERCHANT' and a.owner_id = ?)
                    or (a.owner_type = 'MERCHANT_GROUP' and a.owner_id in (
                        select m.group_id::text
                        from limit_management.merchant_group_memberships m
                        where m.merchant_id = ?
                          and m.valid_from <= ?
                          and (m.valid_to is null or m.valid_to > ?)
                    ))
                  )
                order by r.code asc, r.version asc
                """, (rs, rowNum) -> mapCandidate(rs, Set.of()),
                ts, ts, merchantId, merchantId, ts, ts);
        if (rows.isEmpty()) {
            return rows;
        }
        Map<UUID, Set<String>> operationTypesByRule = loadOperationTypesForRules(
                rows.stream().map(EffectiveLimitCandidate::ruleId).distinct().toList());
        return rows.stream()
                .map(row -> withOperationTypes(row, operationTypesByRule.getOrDefault(row.ruleId(), Set.of())))
                .toList();
    }

    @Override
    public Optional<Integer> findLatestManifestVersion() {
        Integer version = jdbcTemplate.queryForObject(
                "select max(version) from limit_management.runtime_manifests", Integer.class);
        return Optional.ofNullable(version);
    }

    private EffectiveLimitCandidate mapCandidate(ResultSet rs, Set<String> operationTypes) throws SQLException {
        String period = rs.getString("period");
        String targetType = rs.getString("target_type");
        return new EffectiveLimitCandidate(
                rs.getObject("assignment_id", UUID.class),
                AssignmentOwnerType.valueOf(rs.getString("owner_type")),
                rs.getString("owner_id"),
                LimitMode.valueOf(rs.getString("limit_mode")),
                rs.getObject("rule_id", UUID.class),
                rs.getString("code"),
                rs.getInt("version"),
                OperationDirection.valueOf(rs.getString("direction")),
                RuleMetric.valueOf(rs.getString("metric")),
                period == null ? null : RulePeriod.valueOf(period),
                targetType == null ? null : LimitTargetType.valueOf(targetType),
                operationTypes,
                rs.getBigDecimal("limit_value")
        );
    }

    private EffectiveLimitCandidate withOperationTypes(EffectiveLimitCandidate candidate, Set<String> operationTypes) {
        return new EffectiveLimitCandidate(
                candidate.assignmentId(),
                candidate.ownerLevel(),
                candidate.ownerId(),
                candidate.mode(),
                candidate.ruleId(),
                candidate.ruleCode(),
                candidate.ruleVersion(),
                candidate.direction(),
                candidate.metric(),
                candidate.period(),
                candidate.limitTargetType(),
                operationTypes,
                candidate.limitValue());
    }

    /** Batches the per-rule operationTypes lookup into a single IN-query (mirrors PostgresLimitRuleRepository). */
    private Map<UUID, Set<String>> loadOperationTypesForRules(List<UUID> ruleIds) {
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = ruleIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        Map<UUID, Set<String>> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                "select rule_id, operation_type_code from limit_management.limit_rule_operation_type "
                        + "where rule_id in (" + placeholders + ") order by rule_id, operation_type_code",
                (RowCallbackHandler) rs -> {
                    UUID ruleId = rs.getObject("rule_id", UUID.class);
                    result.computeIfAbsent(ruleId, key -> new LinkedHashSet<>())
                            .add(rs.getString("operation_type_code"));
                },
                ruleIds.toArray());
        return result;
    }
}
