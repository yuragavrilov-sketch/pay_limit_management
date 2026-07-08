package ru.copperside.paylimits.management.common.persistence;

import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;

/**
 * Shared SELECT fragment and row mapper for {@code limit_management.limit_rules}, extracted from
 * identical copies in {@code PostgresLimitRuleRepository} and
 * {@code PostgresRuntimeManifestRepository}. Keep the column list, aliasing and the
 * {@link LimitRule} field order EXACTLY as-is: this backs both the CRUD read path and the runtime
 * manifest compilation read path, and any drift changes what gets checksummed.
 */
public final class LimitRuleRowMapper {

    public static final String RULE_SELECT = """
            select r.id, r.code, r.version, r.name, r.direction,
                   r.attribute_selector_type, r.attribute_selector_value,
                   r.target_type, r.metric, r.period, r.aggregation_scope, r.currency,
                   r.interval_minutes, r.limit_value, r.error_message_template, r.status,
                   r.created_at, r.updated_at, r.activated_at, r.disabled_at
            from limit_management.limit_rules r
            """;

    private LimitRuleRowMapper() {
    }

    public static LimitRule mapRule(ResultSet rs, Set<String> operationTypes) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Timestamp activatedAt = rs.getTimestamp("activated_at");
        Timestamp disabledAt = rs.getTimestamp("disabled_at");
        String period = rs.getString("period");
        String scope = rs.getString("aggregation_scope");
        String targetType = rs.getString("target_type");
        Integer intervalMinutes = (Integer) rs.getObject("interval_minutes");
        return new LimitRule(
                id,
                rs.getString("code"),
                rs.getInt("version"),
                rs.getString("name"),
                operationTypes,
                OperationDirection.valueOf(rs.getString("direction")),
                new Measure(
                        RuleMetric.valueOf(rs.getString("metric")),
                        period == null ? null : RulePeriod.valueOf(period),
                        scope == null ? null : AggregationScope.valueOf(scope),
                        rs.getString("currency"),
                        intervalMinutes),
                targetType == null ? null : LimitTargetType.valueOf(targetType),
                rs.getBigDecimal("limit_value"),
                rs.getString("error_message_template"),
                new RuleSelector<>(
                        AttributeSelectorType.valueOf(rs.getString("attribute_selector_type")),
                        rs.getString("attribute_selector_value")
                ),
                RuleStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                activatedAt == null ? null : activatedAt.toInstant(),
                disabledAt == null ? null : disabledAt.toInstant()
        );
    }

    public static LimitRule withOperationTypes(LimitRule rule, Set<String> operationTypes) {
        return new LimitRule(
                rule.id(), rule.code(), rule.version(), rule.name(), operationTypes, rule.direction(),
                rule.measure(), rule.limitTargetType(), rule.limitValue(), rule.errorMessageTemplate(),
                rule.attributeSelector(), rule.status(), rule.createdAt(), rule.updatedAt(),
                rule.activatedAt(), rule.disabledAt());
    }
}
