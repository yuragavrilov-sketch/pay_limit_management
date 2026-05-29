package ru.copperside.paylimits.management.runtimeconfig.adapter.out.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;
import ru.copperside.paylimits.management.runtimeconfig.application.RuntimeManifestCanonicalJson;
import ru.copperside.paylimits.management.runtimeconfig.application.port.out.RuntimeManifestRepository;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestPayload;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestProblemException;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestStatus;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
public class PostgresRuntimeManifestRepository implements RuntimeManifestRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RuntimeManifestCanonicalJson canonicalJson;
    private final ObjectMapper objectMapper;

    public PostgresRuntimeManifestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.canonicalJson = new RuntimeManifestCanonicalJson();
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
    public List<OperationType> listOperationTypesForCompilation() {
        return jdbcTemplate.query("""
                select id, code, name, family_code, direction, enabled, sort_order, created_at, updated_at
                from limit_management.operation_types
                order by sort_order asc, code asc
                """, (rs, rowNum) -> mapOperationType(rs));
    }

    @Override
    public List<RuntimeCompiledAssignment> listEnabledAssignmentsForCompilation() {
        return jdbcTemplate.query("""
                select a.id as assignment_id, a.rule_id, r.code as rule_code,
                       a.owner_type, a.owner_id, a.limit_mode, a.limit_value,
                       a.valid_from, a.valid_to
                from limit_management.limit_assignments a
                join limit_management.limit_rules r on r.id = a.rule_id
                where a.enabled = true
                  and r.status = 'ACTIVE'
                order by r.code asc, a.owner_type asc, a.owner_id asc, a.id asc
                """, (rs, rowNum) -> mapAssignment(rs));
    }

    @Override
    public List<RuntimeMerchantGroupMembership> listMembershipsForCompilation() {
        return jdbcTemplate.query("""
                select m.id as membership_id, m.merchant_id, m.group_type_id, m.group_id,
                       m.valid_from, m.valid_to
                from limit_management.merchant_group_memberships m
                join limit_management.merchant_groups g
                  on g.id = m.group_id and g.type_id = m.group_type_id
                join limit_management.merchant_group_types t
                  on t.id = m.group_type_id
                where g.enabled = true
                  and t.enabled = true
                order by m.merchant_id asc, m.group_type_id asc, m.valid_from asc, m.id asc
                """, (rs, rowNum) -> mapMembership(rs));
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public RuntimeManifest saveCompiledManifest(CompiledRuntimeManifestFactory factory) {
        jdbcTemplate.execute("lock table limit_management.runtime_manifests in exclusive mode");
        Integer maxVersion = jdbcTemplate.queryForObject("""
                select coalesce(max(version), 0)
                from limit_management.runtime_manifests
                """, Integer.class);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;
        RuntimeManifest manifest = factory.create(nextVersion);
        validateManifest(manifest);

        RuntimeManifestPayload payload = manifest.payload();
        String payloadJson = new String(canonicalJson.bytes(payload), StandardCharsets.UTF_8);
        try {
            jdbcTemplate.update("""
                    insert into limit_management.runtime_manifests
                        (id, version, status, checksum, created_at, effective_from,
                         rule_count, assignment_count, membership_count, payload_json)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    manifest.id(),
                    payload.version(),
                    payload.status().name(),
                    manifest.checksum(),
                    Timestamp.from(payload.createdAt()),
                    Timestamp.from(payload.effectiveFrom()),
                    payload.ruleCount(),
                    payload.assignmentCount(),
                    payload.membershipCount(),
                    payloadJson);

            for (int position = 0; position < payload.rules().size(); position++) {
                var rule = payload.rules().get(position);
                jdbcTemplate.update("""
                        insert into limit_management.runtime_manifest_rules
                            (manifest_id, rule_id, position, payload_json)
                        values (?, ?, ?, ?::jsonb)
                        """,
                        manifest.id(), rule.ruleId(), position, writeJson(rule));
            }
            for (int position = 0; position < payload.assignments().size(); position++) {
                RuntimeCompiledAssignment assignment = payload.assignments().get(position);
                jdbcTemplate.update("""
                        insert into limit_management.runtime_manifest_assignments
                            (manifest_id, assignment_id, position, payload_json)
                        values (?, ?, ?, ?::jsonb)
                        """,
                        manifest.id(), assignment.assignmentId(), position, writeJson(assignment));
            }
            for (int position = 0; position < payload.memberships().size(); position++) {
                RuntimeMerchantGroupMembership membership = payload.memberships().get(position);
                jdbcTemplate.update("""
                        insert into limit_management.runtime_manifest_memberships
                            (manifest_id, membership_id, position, payload_json)
                        values (?, ?, ?, ?::jsonb)
                        """,
                        manifest.id(), membership.membershipId(), position, writeJson(membership));
            }
            return manifest;
        } catch (DataIntegrityViolationException ex) {
            throw mapIntegrityViolation(ex);
        }
    }

    @Override
    public Optional<RuntimeManifest> findManifest(UUID id) {
        return jdbcTemplate.query(manifestSelect() + " where id = ?", (rs, rowNum) -> mapManifest(rs), id)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<RuntimeManifest> findEffectiveManifest(Instant at) {
        return jdbcTemplate.query(manifestSelect() + """
                where effective_from <= ?
                order by version desc
                limit 1
                """, (rs, rowNum) -> mapManifest(rs), Timestamp.from(at)).stream().findFirst();
    }

    @Override
    public List<RuntimeManifestDescriptor> listScheduledManifests(Instant after, int limit) {
        return jdbcTemplate.query("""
                select id, version, checksum, created_at, effective_from
                from limit_management.runtime_manifests
                where effective_from > ?
                order by effective_from asc, version asc
                limit ?
                """, (rs, rowNum) -> mapDescriptor(rs), Timestamp.from(after), Math.max(1, limit));
    }

    private void validateManifest(RuntimeManifest manifest) {
        if (manifest == null || manifest.payload() == null) {
            throw new IllegalArgumentException("Runtime manifest payload must be present");
        }
        RuntimeManifestPayload payload = manifest.payload();
        if (payload.rules() == null || payload.ruleCount() != payload.rules().size()) {
            throw new IllegalArgumentException("Runtime manifest rule count does not match rules size");
        }
        if (payload.assignments() == null || payload.assignmentCount() != payload.assignments().size()) {
            throw new IllegalArgumentException("Runtime manifest assignment count does not match assignments size");
        }
        if (payload.memberships() == null || payload.membershipCount() != payload.memberships().size()) {
            throw new IllegalArgumentException("Runtime manifest membership count does not match memberships size");
        }
        if (manifest.version() != payload.version()
                || manifest.status() != payload.status()
                || !Objects.equals(manifest.createdAt(), payload.createdAt())
                || !Objects.equals(manifest.effectiveFrom(), payload.effectiveFrom())
                || manifest.ruleCount() != payload.ruleCount()
                || manifest.assignmentCount() != payload.assignmentCount()
                || manifest.membershipCount() != payload.membershipCount()
                || !Objects.equals(manifest.rules(), payload.rules())
                || !Objects.equals(manifest.assignments(), payload.assignments())
                || !Objects.equals(manifest.memberships(), payload.memberships())
                || !Objects.equals(manifest.diagnostics(), payload.diagnostics())) {
            throw new IllegalArgumentException("Runtime manifest payload does not match top-level fields");
        }
        String expectedChecksum = canonicalJson.checksum(payload);
        if (!Objects.equals(manifest.checksum(), expectedChecksum)) {
            throw new IllegalArgumentException("Runtime manifest checksum does not match payload");
        }
    }

    private String manifestSelect() {
        return """
                select id, version, status, checksum, created_at, effective_from,
                       rule_count, assignment_count, membership_count, payload_json::text as payload_json
                from limit_management.runtime_manifests
                """;
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

    private RuntimeManifest mapManifest(ResultSet rs) throws SQLException {
        RuntimeManifestPayload payload = readPayload(rs.getString("payload_json"));
        return new RuntimeManifest(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                RuntimeManifestStatus.valueOf(rs.getString("status")),
                rs.getString("checksum"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("effective_from").toInstant(),
                rs.getInt("rule_count"),
                rs.getInt("assignment_count"),
                rs.getInt("membership_count"),
                payload.rules(),
                payload.assignments(),
                payload.memberships(),
                payload.diagnostics(),
                payload
        );
    }

    private RuntimeManifestDescriptor mapDescriptor(ResultSet rs) throws SQLException {
        return new RuntimeManifestDescriptor(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                rs.getString("checksum"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("effective_from").toInstant()
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

    private RuntimeCompiledAssignment mapAssignment(ResultSet rs) throws SQLException {
        Timestamp validTo = rs.getTimestamp("valid_to");
        return new RuntimeCompiledAssignment(
                rs.getObject("assignment_id", UUID.class),
                rs.getObject("rule_id", UUID.class),
                rs.getString("rule_code"),
                AssignmentOwnerType.valueOf(rs.getString("owner_type")),
                rs.getString("owner_id"),
                LimitMode.valueOf(rs.getString("limit_mode")),
                rs.getString("limit_value"),
                rs.getTimestamp("valid_from").toInstant(),
                validTo == null ? null : validTo.toInstant()
        );
    }

    private RuntimeMerchantGroupMembership mapMembership(ResultSet rs) throws SQLException {
        Timestamp validTo = rs.getTimestamp("valid_to");
        return new RuntimeMerchantGroupMembership(
                rs.getObject("membership_id", UUID.class),
                rs.getString("merchant_id"),
                rs.getObject("group_type_id", UUID.class),
                rs.getObject("group_id", UUID.class),
                rs.getTimestamp("valid_from").toInstant(),
                validTo == null ? null : validTo.toInstant()
        );
    }

    private RuntimeManifestPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, RuntimeManifestPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize runtime manifest payload", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize runtime manifest item", e);
        }
    }

    private RuntimeManifestProblemException mapIntegrityViolation(DataIntegrityViolationException ex) {
        String message = rootMessage(ex);
        if (message.contains("runtime_manifests_checksum_uk")) {
            return new RuntimeManifestProblemException("RUNTIME_MANIFEST_CONFLICT", "Runtime manifest checksum already exists");
        }
        if (message.contains("runtime_manifests_version_uk")) {
            return new RuntimeManifestProblemException("RUNTIME_MANIFEST_CONFLICT", "Runtime manifest version already exists");
        }
        if (message.contains("runtime_manifest")) {
            return new RuntimeManifestProblemException("RUNTIME_MANIFEST_CONFLICT", "Runtime manifest data violates repository constraints");
        }
        return new RuntimeManifestProblemException("RUNTIME_MANIFEST_CONFLICT", "Runtime manifest data violates repository constraints");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? throwable.getMessage() : current.getMessage();
    }
}
