package ru.copperside.paylimits.management.limitrule.adapter.out.postgres;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.copperside.paylimits.management.common.persistence.LimitRuleRowMapper;
import ru.copperside.paylimits.management.common.persistence.RuleOperationTypeLoader;
import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.DictionaryItem;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
public class PostgresLimitRuleRepository implements LimitRuleRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresLimitRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OperationType> listOperationTypes() {
        return jdbcTemplate.query("""
                select id, code, name, family_code, direction, counterparty_type, enabled, sort_order, created_at, updated_at
                from limit_management.operation_types
                order by sort_order asc, code asc
                """, (rs, rowNum) -> mapOperationType(rs));
    }

    @Override
    public RuleDictionaries getRuleDictionaries() {
        return new RuleDictionaries(
                listDictionary("operation_families"),
                listOperationTypes(),
                listDictionary("payment_systems"),
                listDictionary("issuer_countries"),
                listDictionary("issuer_banks"),
                listDictionary("bins"),
                listDictionary("card_types"),
                listDictionary("card_levels"),
                Arrays.asList(OperationDirection.values()),
                Arrays.asList(AttributeSelectorType.values()),
                Arrays.asList(LimitTargetType.values()),
                Arrays.asList(RuleMetric.values()),
                Arrays.asList(RulePeriod.values()),
                Arrays.asList(CounterpartyType.values()),
                Arrays.asList(AggregationScope.values())
        );
    }

    @Override
    public Optional<OperationType> findOperationType(UUID id) {
        return jdbcTemplate.query("""
                select id, code, name, family_code, direction, counterparty_type, enabled, sort_order, created_at, updated_at
                from limit_management.operation_types
                where id = ?
                """, (rs, rowNum) -> mapOperationType(rs), id).stream().findFirst();
    }

    @Override
    public Optional<OperationType> findOperationTypeByCode(String code) {
        return jdbcTemplate.query("""
                select id, code, name, family_code, direction, counterparty_type, enabled, sort_order, created_at, updated_at
                from limit_management.operation_types
                where code = ?
                """, (rs, rowNum) -> mapOperationType(rs), code).stream().findFirst();
    }

    @Override
    public boolean attributeValueExists(AttributeSelectorType type, String code) {
        return switch (type) {
            case NONE -> code == null;
            case PAYMENT_SYSTEM -> existsEnabledValue("payment_systems", code);
            case ISSUER_COUNTRY -> existsEnabledValue("issuer_countries", code);
            case BIN -> existsEnabledValue("bins", code);
            case BANK -> existsEnabledValue("issuer_banks", code);
            case CARD_TYPE -> existsEnabledValue("card_types", code);
            case CARD_LEVEL -> existsEnabledValue("card_levels", code);
        };
    }

    @Override
    public OperationType saveOperationType(OperationType type) {
        try {
            jdbcTemplate.update("""
                    insert into limit_management.operation_types
                        (id, code, name, family_code, direction, counterparty_type, enabled, sort_order, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    type.id(), type.code(), type.name(), type.familyCode(), type.direction().name(),
                    type.counterpartyType().name(), type.enabled(),
                    type.sortOrder(), Timestamp.from(type.createdAt()), Timestamp.from(type.updatedAt()));
            return type;
        } catch (DataIntegrityViolationException ex) {
            throw mapIntegrityViolation(ex);
        }
    }

    @Override
    public OperationType updateOperationType(OperationType type) {
        try {
            jdbcTemplate.update("""
                    update limit_management.operation_types
                    set name = ?, family_code = ?, direction = ?, counterparty_type = ?, enabled = ?, sort_order = ?, updated_at = ?
                    where id = ?
                    """,
                    type.name(), type.familyCode(), type.direction().name(), type.counterpartyType().name(),
                    type.enabled(), type.sortOrder(), Timestamp.from(type.updatedAt()), type.id());
            return type;
        } catch (DataIntegrityViolationException ex) {
            throw mapIntegrityViolation(ex);
        }
    }

    @Override
    public boolean hasActiveRulesForOperationTypeCode(String operationTypeCode) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists (
                    select 1
                    from limit_management.limit_rule_operation_type ot
                    join limit_management.limit_rules r on r.id = ot.rule_id
                    where ot.operation_type_code = ? and r.status = 'ACTIVE'
                )
                """, Boolean.class, operationTypeCode);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<LimitRule> listRules() {
        // Batch operationTypes for the whole page instead of one junction-table query per rule (N+1):
        // map rows with a placeholder set first, then attach the real sets from a single IN-query.
        List<LimitRule> rules = jdbcTemplate.query(
                LimitRuleRowMapper.RULE_SELECT + " order by r.code asc, r.version asc",
                (rs, rowNum) -> LimitRuleRowMapper.mapRule(rs, Set.of()));
        if (rules.isEmpty()) {
            return rules;
        }
        Map<UUID, Set<String>> operationTypesByRule =
                RuleOperationTypeLoader.loadForRules(jdbcTemplate, rules.stream().map(LimitRule::id).toList());
        return rules.stream()
                .map(rule -> LimitRuleRowMapper.withOperationTypes(rule, operationTypesByRule.getOrDefault(rule.id(), Set.of())))
                .toList();
    }

    @Override
    public Optional<LimitRule> findRule(UUID id) {
        return jdbcTemplate.query(LimitRuleRowMapper.RULE_SELECT + " where r.id = ?", (rs, rowNum) -> mapRule(rs), id)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<LimitRule> findDraftByCode(String code) {
        return jdbcTemplate.query(LimitRuleRowMapper.RULE_SELECT + " where r.code = ? and r.status = 'DRAFT'",
                        (rs, rowNum) -> mapRule(rs), code)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<LimitRule> findActiveByCode(String code) {
        return jdbcTemplate.query(LimitRuleRowMapper.RULE_SELECT + " where r.code = ? and r.status = 'ACTIVE'",
                        (rs, rowNum) -> mapRule(rs), code)
                .stream()
                .findFirst();
    }

    @Override
    public int nextVersion(String code) {
        Integer maxVersion = jdbcTemplate.queryForObject("""
                select coalesce(max(version), 0)
                from limit_management.limit_rules
                where code = ?
                """, Integer.class, code);
        return (maxVersion == null ? 0 : maxVersion) + 1;
    }

    @Override
    @Transactional
    public LimitRule saveRule(LimitRule rule) {
        try {
            jdbcTemplate.update("""
                    insert into limit_management.limit_rules
                        (id, code, version, name, direction,
                         attribute_selector_type, attribute_selector_value, target_type,
                         metric, period, aggregation_scope, currency, interval_minutes,
                         limit_value, error_message_template,
                         status, created_at, updated_at, activated_at, disabled_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    rule.id(), rule.code(), rule.version(), rule.name(), rule.direction().name(),
                    rule.attributeSelector().type().name(), rule.attributeSelector().value(),
                    rule.limitTargetType() == null ? null : rule.limitTargetType().name(),
                    rule.measure().metric().name(),
                    rule.measure().period() == null ? null : rule.measure().period().name(),
                    rule.measure().aggregationScope() == null ? null : rule.measure().aggregationScope().name(),
                    rule.measure().currency(),
                    rule.measure().intervalMinutes(),
                    rule.limitValue(),
                    rule.errorMessageTemplate(),
                    rule.status().name(),
                    Timestamp.from(rule.createdAt()), Timestamp.from(rule.updatedAt()),
                    toTimestamp(rule.activatedAt()), toTimestamp(rule.disabledAt()));
            replaceOperationTypes(rule.id(), rule.operationTypes());
            return rule;
        } catch (DataIntegrityViolationException ex) {
            throw mapIntegrityViolation(ex);
        }
    }

    @Override
    @Transactional
    public LimitRule updateRule(LimitRule rule) {
        try {
            jdbcTemplate.update("""
                    update limit_management.limit_rules
                    set name = ?, direction = ?,
                        attribute_selector_type = ?, attribute_selector_value = ?, target_type = ?,
                        metric = ?, period = ?, aggregation_scope = ?, currency = ?, interval_minutes = ?,
                        limit_value = ?, error_message_template = ?,
                        status = ?, updated_at = ?, activated_at = ?, disabled_at = ?
                    where id = ?
                    """,
                    rule.name(), rule.direction().name(),
                    rule.attributeSelector().type().name(), rule.attributeSelector().value(),
                    rule.limitTargetType() == null ? null : rule.limitTargetType().name(),
                    rule.measure().metric().name(),
                    rule.measure().period() == null ? null : rule.measure().period().name(),
                    rule.measure().aggregationScope() == null ? null : rule.measure().aggregationScope().name(),
                    rule.measure().currency(),
                    rule.measure().intervalMinutes(),
                    rule.limitValue(),
                    rule.errorMessageTemplate(),
                    rule.status().name(), Timestamp.from(rule.updatedAt()), toTimestamp(rule.activatedAt()),
                    toTimestamp(rule.disabledAt()), rule.id());
            replaceOperationTypes(rule.id(), rule.operationTypes());
            return findRule(rule.id())
                    .orElseThrow(() -> new LimitRuleProblemException("RULE_NOT_FOUND", "Rule not found"));
        } catch (DataIntegrityViolationException ex) {
            throw mapIntegrityViolation(ex);
        }
    }

    private void replaceOperationTypes(UUID ruleId, Set<String> codes) {
        jdbcTemplate.update("delete from limit_management.limit_rule_operation_type where rule_id = ?", ruleId);
        for (String code : codes) {
            jdbcTemplate.update(
                    "insert into limit_management.limit_rule_operation_type (rule_id, operation_type_code) values (?, ?)",
                    ruleId, code);
        }
    }

    private List<DictionaryItem> listDictionary(String table) {
        return jdbcTemplate.query("""
                select code, name, enabled, sort_order, created_at, updated_at
                from limit_management.%s
                order by sort_order asc, code asc
                """.formatted(table), (rs, rowNum) -> mapDictionaryItem(rs));
    }

    private boolean existsEnabledValue(String table, String code) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists (
                    select 1
                    from limit_management.%s
                    where code = ?
                      and enabled = true
                )
                """.formatted(table), Boolean.class, code);
        return Boolean.TRUE.equals(exists);
    }

    private DictionaryItem mapDictionaryItem(ResultSet rs) throws SQLException {
        return new DictionaryItem(
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getInt("sort_order"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private OperationType mapOperationType(ResultSet rs) throws SQLException {
        return new OperationType(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("family_code"),
                OperationDirection.valueOf(rs.getString("direction")),
                CounterpartyType.valueOf(rs.getString("counterparty_type")),
                rs.getBoolean("enabled"),
                rs.getInt("sort_order"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    /** Single-rule mapping: still one junction-table query per row (fine for {@code findRule}/etc.). */
    private LimitRule mapRule(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return LimitRuleRowMapper.mapRule(rs, RuleOperationTypeLoader.loadForRule(jdbcTemplate, id));
    }

    private Timestamp toTimestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private LimitRuleProblemException mapIntegrityViolation(DataIntegrityViolationException ex) {
        String message = rootMessage(ex);
        if (message.contains("operation_types_code_uk")) {
            return new LimitRuleProblemException("OPERATION_TYPE_CODE_CONFLICT", "Operation type code already exists");
        }
        if (message.contains("operation_types_direction_chk")) {
            return new LimitRuleProblemException("OPERATION_TYPE_INVALID_DIRECTION",
                    "Operation type direction must be IN or OUT");
        }
        if (message.contains("limit_rules_code_version_uk")) {
            return new LimitRuleProblemException("RULE_CODE_CONFLICT", "Rule code and version already exist");
        }
        if (message.contains("limit_rules_one_draft_per_code_uk")) {
            return new LimitRuleProblemException("RULE_DRAFT_EXISTS", "Draft rule already exists");
        }
        if (message.contains("limit_rules_one_active_per_code_uk")) {
            return new LimitRuleProblemException("RULE_STATUS_CONFLICT", "Another active rule already exists");
        }
        if (message.contains("limit_rules_") || message.contains("limit_rule_operation_type")) {
            return new LimitRuleProblemException("INVALID_RULE_DEFINITION", "Rule definition is invalid");
        }
        return new LimitRuleProblemException("INVALID_RULE_DEFINITION", "Limit rule data violates repository constraints");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? throwable.getMessage() : current.getMessage();
    }
}
