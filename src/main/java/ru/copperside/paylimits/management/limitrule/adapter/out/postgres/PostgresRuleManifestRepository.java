package ru.copperside.paylimits.management.limitrule.adapter.out.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.copperside.paylimits.management.limitrule.application.RuleManifestCanonicalJson;
import ru.copperside.paylimits.management.limitrule.application.port.out.RuleManifestRepository;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CompiledRule;
import ru.copperside.paylimits.management.limitrule.domain.DictionaryItem;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifest;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestPayload;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestStatus;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Repository
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
public class PostgresRuleManifestRepository implements RuleManifestRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RuleManifestCanonicalJson canonicalJson;
    private final ObjectMapper objectMapper;

    public PostgresRuleManifestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.canonicalJson = new RuleManifestCanonicalJson();
        this.objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .build();
    }

    @Override
    public List<LimitRule> listActiveRulesForCompilation() {
        return jdbcTemplate.query(ruleSelect() + """
                where r.status = 'ACTIVE'
                order by r.code asc, r.version asc, r.id asc
                """, (rs, rowNum) -> mapRule(rs));
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
                Arrays.asList(OperationSelectorType.values()),
                Arrays.asList(AttributeSelectorType.values()),
                Arrays.asList(LimitTargetType.values()),
                Arrays.asList(RuleMetric.values()),
                Arrays.asList(RulePeriod.values())
        );
    }

    @Override
    @Transactional
    public RuleManifest saveNextManifest(Function<Integer, RuleManifest> manifestFactory) {
        jdbcTemplate.execute("lock table limit_management.rule_manifests in exclusive mode");
        Integer maxVersion = jdbcTemplate.queryForObject("""
                select coalesce(max(version), 0)
                from limit_management.rule_manifests
                """, Integer.class);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;
        RuleManifest manifest = manifestFactory.apply(nextVersion);
        String payloadJson = new String(canonicalJson.bytes(manifest.payload()));

        jdbcTemplate.update("""
                insert into limit_management.rule_manifests
                    (id, version, status, checksum, rule_count, payload_json, created_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                manifest.id(), manifest.version(), manifest.status().name(), manifest.checksum(),
                manifest.ruleCount(), payloadJson, Timestamp.from(manifest.createdAt()));

        for (int position = 0; position < manifest.rules().size(); position++) {
            CompiledRule rule = manifest.rules().get(position);
            jdbcTemplate.update("""
                    insert into limit_management.rule_manifest_rules
                        (manifest_id, rule_id, rule_code, rule_version, position, payload_json)
                    values (?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    manifest.id(), rule.ruleId(), rule.code(), rule.version(), position, writeJson(rule));
        }
        return manifest;
    }

    @Override
    public Optional<RuleManifest> findLatestManifest() {
        return jdbcTemplate.query("""
                select id, version, status, checksum, rule_count, payload_json::text as payload_json, created_at
                from limit_management.rule_manifests
                order by version desc
                limit 1
                """, (rs, rowNum) -> mapManifest(rs)).stream().findFirst();
    }

    @Override
    public Optional<RuleManifest> findManifest(UUID id) {
        return jdbcTemplate.query("""
                select id, version, status, checksum, rule_count, payload_json::text as payload_json, created_at
                from limit_management.rule_manifests
                where id = ?
                """, (rs, rowNum) -> mapManifest(rs), id).stream().findFirst();
    }

    private List<OperationType> listOperationTypes() {
        return jdbcTemplate.query("""
                select id, code, name, family_code, direction, enabled, sort_order, created_at, updated_at
                from limit_management.operation_types
                order by sort_order asc, code asc
                """, (rs, rowNum) -> mapOperationType(rs));
    }

    private List<DictionaryItem> listDictionary(String table) {
        return jdbcTemplate.query("""
                select code, name, enabled, sort_order, created_at, updated_at
                from limit_management.%s
                order by sort_order asc, code asc
                """.formatted(table), (rs, rowNum) -> mapDictionaryItem(rs));
    }

    private RuleManifest mapManifest(ResultSet rs) throws SQLException {
        RuleManifestPayload payload = readPayload(rs.getString("payload_json"));
        return new RuleManifest(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                RuleManifestStatus.valueOf(rs.getString("status")),
                rs.getString("checksum"),
                rs.getInt("rule_count"),
                rs.getTimestamp("created_at").toInstant(),
                payload.rules(),
                payload.diagnostics(),
                payload
        );
    }

    private String ruleSelect() {
        return """
                select r.id, r.code, r.version, r.name,
                       r.operation_selector_type, r.operation_selector_value, r.direction,
                       r.attribute_selector_type, r.attribute_selector_value,
                       r.target_type, r.metric, r.period, r.currency, r.status,
                       r.created_at, r.updated_at, r.activated_at, r.disabled_at
                from limit_management.limit_rules r
                """;
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
                rs.getBoolean("enabled"),
                rs.getInt("sort_order"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private LimitRule mapRule(ResultSet rs) throws SQLException {
        Timestamp activatedAt = rs.getTimestamp("activated_at");
        Timestamp disabledAt = rs.getTimestamp("disabled_at");
        return new LimitRule(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getInt("version"),
                rs.getString("name"),
                new RuleSelector<>(
                        OperationSelectorType.valueOf(rs.getString("operation_selector_type")),
                        rs.getString("operation_selector_value")
                ),
                OperationDirection.valueOf(rs.getString("direction")),
                new RuleSelector<>(
                        AttributeSelectorType.valueOf(rs.getString("attribute_selector_type")),
                        rs.getString("attribute_selector_value")
                ),
                LimitTargetType.valueOf(rs.getString("target_type")),
                RuleMetric.valueOf(rs.getString("metric")),
                RulePeriod.valueOf(rs.getString("period")),
                rs.getString("currency"),
                RuleStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                activatedAt == null ? null : activatedAt.toInstant(),
                disabledAt == null ? null : disabledAt.toInstant()
        );
    }

    private RuleManifestPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, RuleManifestPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize rule manifest payload", e);
        }
    }

    private String writeJson(CompiledRule rule) {
        try {
            return objectMapper.writeValueAsString(rule);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize compiled rule", e);
        }
    }
}
