package ru.copperside.paylimits.management.common.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared reader for the {@code limit_management.limit_rule_operation_type} junction table.
 * Extracted from three previously byte-for-byte identical copies in
 * {@code PostgresLimitRuleRepository}, {@code PostgresRuntimeManifestRepository} and
 * {@code PostgresEffectiveLimitsRepository}. The query, row ordering and {@link Set} construction
 * (a {@link LinkedHashSet} populated in {@code operation_type_code} order) must stay EXACTLY as
 * they are: the runtime manifest checksum is derived from the {@code operationTypes} each caller
 * attaches to a {@link ru.copperside.paylimits.management.limitrule.domain.LimitRule}, so any
 * change here is checksum-sensitive even though the compilers additionally sort the set.
 */
public final class RuleOperationTypeLoader {

    private RuleOperationTypeLoader() {
    }

    /** Batches the per-rule operationTypes lookup into a single IN-query for multi-rule reads. */
    public static Map<UUID, Set<String>> loadForRules(JdbcTemplate jdbcTemplate, Collection<UUID> ruleIds) {
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

    /** Single-rule lookup, shared by callers that map one row (and its junction rows) at a time. */
    public static Set<String> loadForRule(JdbcTemplate jdbcTemplate, UUID ruleId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "select operation_type_code from limit_management.limit_rule_operation_type where rule_id = ? order by operation_type_code",
                String.class, ruleId));
    }
}
