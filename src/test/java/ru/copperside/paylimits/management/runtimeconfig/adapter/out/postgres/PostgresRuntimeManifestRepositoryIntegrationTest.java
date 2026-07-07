package ru.copperside.paylimits.management.runtimeconfig.adapter.out.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.runtimeconfig.application.RuntimeManifestCanonicalJson;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledRule;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestPayload;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestStatus;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeOperationType;
import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PostgresRuntimeManifestRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private final RuntimeManifestCanonicalJson canonicalJson = new RuntimeManifestCanonicalJson();

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "limit_management");
        registry.add("spring.flyway.default-schema", () -> "limit_management");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostgresRuntimeManifestRepository repository;

    @Test
    void savesManifestAndPayloadRows() {
        SnapshotIds ids = insertSnapshot("SAVE");
        RuntimeManifest manifest = repository.saveCompiledManifest(version -> manifest(
                version,
                ids,
                Instant.parse("2026-05-29T10:00:00Z"),
                Instant.parse("2026-05-29T10:15:00Z")
        ));

        assertThat(repository.findManifest(manifest.id())).contains(manifest);
        assertThat(childRows("runtime_manifest_rules", manifest.id())).isEqualTo(1);
        assertThat(childRows("runtime_manifest_assignments", manifest.id())).isEqualTo(1);
        assertThat(childRows("runtime_manifest_memberships", manifest.id())).isEqualTo(1);
    }

    @Test
    void persistsSchemaVersionAndOperationTypesOnCompiledAndReadManifest() {
        SnapshotIds ids = insertSnapshot("SCHEMA_VERSION");
        RuntimeManifest manifest = repository.saveCompiledManifest(version -> manifest(
                version,
                ids,
                Instant.parse("2026-05-29T10:00:00Z"),
                Instant.parse("2026-05-29T10:15:00Z")
        ));

        Integer schemaVersionColumn = jdbcTemplate.queryForObject("""
                select schema_version
                from limit_management.runtime_manifests
                where id = ?
                """, Integer.class, manifest.id());
        assertThat(schemaVersionColumn).isEqualTo(2);

        RuntimeManifest reloaded = repository.findManifest(manifest.id()).orElseThrow();
        assertThat(reloaded.schemaVersion()).isEqualTo(2);
        assertThat(reloaded.businessTimezone()).isEqualTo("Europe/Moscow");
        assertThat(reloaded.operationTypes()).isNotEmpty();
    }

    @Test
    void listsEnabledOperationTypesSortedByCodeForManifestCompilation() {
        List<RuntimeOperationType> operationTypes = repository.listOperationTypesForManifest();

        assertThat(operationTypes).isNotEmpty();
        assertThat(operationTypes).extracting(RuntimeOperationType::code).isSorted();
        assertThat(operationTypes).extracting(RuntimeOperationType::code).contains("SBP_C2B", "SBP_B2C");
    }

    @Test
    void findEffectiveReturnsHighestDueVersion() {
        SnapshotIds firstIds = insertSnapshot("EFFECTIVE_FIRST");
        RuntimeManifest first = repository.saveCompiledManifest(version -> manifest(
                version,
                firstIds,
                Instant.parse("2026-05-29T10:00:00Z"),
                Instant.parse("2026-05-29T10:15:00Z")
        ));
        SnapshotIds secondIds = insertSnapshot("EFFECTIVE_SECOND");
        RuntimeManifest second = repository.saveCompiledManifest(version -> manifest(
                version,
                secondIds,
                Instant.parse("2026-05-29T10:01:00Z"),
                Instant.parse("2026-05-29T10:30:00Z")
        ));

        assertThat(repository.findEffectiveManifest(Instant.parse("2026-05-29T10:20:00Z"))).contains(first);
        assertThat(repository.findEffectiveManifest(Instant.parse("2026-05-29T10:30:00Z"))).contains(second);
    }

    @Test
    void listScheduledReturnsDescriptorsOrderedByEffectiveFromThenVersion() {
        SnapshotIds firstIds = insertSnapshot("SCHEDULED_FIRST");
        RuntimeManifest first = repository.saveCompiledManifest(version -> manifest(
                version,
                firstIds,
                Instant.parse("2026-05-29T10:00:00Z"),
                Instant.parse("2026-05-29T10:30:00Z")
        ));
        SnapshotIds secondIds = insertSnapshot("SCHEDULED_SECOND");
        RuntimeManifest second = repository.saveCompiledManifest(version -> manifest(
                version,
                secondIds,
                Instant.parse("2026-05-29T10:01:00Z"),
                Instant.parse("2026-05-29T10:45:00Z")
        ));

        List<RuntimeManifestDescriptor> descriptors = repository.listScheduledManifests(
                Instant.parse("2026-05-29T10:20:00Z"),
                10
        );

        assertThat(descriptors)
                .extracting(RuntimeManifestDescriptor::id)
                .containsSubsequence(first.id(), second.id());
        assertThat(descriptors)
                .filteredOn(descriptor -> descriptor.id().equals(first.id()))
                .first()
                .satisfies(descriptor -> {
                    assertThat(descriptor.version()).isEqualTo(first.version());
                    assertThat(descriptor.checksum()).isEqualTo(first.checksum());
                    assertThat(descriptor.effectiveFrom()).isEqualTo(first.effectiveFrom());
                });
    }

    @Test
    void findEffectiveReturnsEmptyBeforeFirstManifest() {
        assertThat(repository.findEffectiveManifest(Instant.parse("2026-01-01T00:00:00Z"))).isEmpty();
    }

    @Test
    void listEnabledAssignmentsForCompilationIncludesGlobalWithNullOwnerId() {
        UUID ruleId = UUID.randomUUID();
        String ruleCode = "RULE_RUNTIME_GLOBAL";
        jdbcTemplate.update("""
                insert into limit_management.limit_rules
                    (id, code, version, name, direction,
                     attribute_selector_type, attribute_selector_value, target_type,
                     metric, period, aggregation_scope, currency, interval_minutes,
                     limit_value, error_message_template,
                     status, created_at, updated_at, activated_at, disabled_at)
                values (?, ?, 1, ?, 'IN',
                        'NONE', null, 'PHONE',
                        'AMOUNT', 'DAY', 'OWNER', 'RUB', null,
                        1000.00, 'template',
                        'ACTIVE', now(), now(), now(), null)
                """, ruleId, ruleCode, ruleCode);
        jdbcTemplate.update("""
                insert into limit_management.limit_rule_operation_type (rule_id, operation_type_code)
                values (?, 'SBP_C2B')
                """, ruleId);
        UUID assignmentId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into limit_management.limit_assignments
                    (id, rule_id, owner_type, owner_id, limit_mode,
                     valid_from, valid_to, enabled, created_at, updated_at)
                values (?, ?, 'GLOBAL', null, 'LIMITED',
                        ?, null, true, now(), now())
                """, assignmentId, ruleId, Timestamp.from(Instant.parse("2026-05-29T00:00:00Z")));

        List<RuntimeCompiledAssignment> assignments = repository.listEnabledAssignmentsForCompilation();

        assertThat(assignments)
                .filteredOn(assignment -> assignment.assignmentId().equals(assignmentId))
                .singleElement()
                .satisfies(assignment -> {
                    assertThat(assignment.ownerType()).isEqualTo(AssignmentOwnerType.GLOBAL);
                    assertThat(assignment.ownerId()).isNull();
                    assertThat(assignment.ruleCode()).isEqualTo(ruleCode);
                });
    }

    private RuntimeManifest manifest(int version, SnapshotIds ids, Instant createdAt, Instant effectiveFrom) {
        List<RuntimeCompiledRule> rules = List.of(compiledRule(ids.ruleId(), ids.ruleCode()));
        List<RuntimeCompiledAssignment> assignments = List.of(new RuntimeCompiledAssignment(
                ids.assignmentId(),
                ids.ruleId(),
                ids.ruleCode(),
                AssignmentOwnerType.MERCHANT,
                "502118",
                LimitMode.LIMITED,
                Instant.parse("2026-05-29T00:00:00Z"),
                null
        ));
        List<RuntimeMerchantGroupMembership> memberships = List.of(new RuntimeMerchantGroupMembership(
                ids.membershipId(),
                "502118",
                ids.groupTypeId(),
                ids.groupId(),
                Instant.parse("2026-05-01T00:00:00Z"),
                null
        ));
        RuntimeManifestPayload payload = new RuntimeManifestPayload(
                2,
                "Europe/Moscow",
                List.of(new RuntimeOperationType("SBP_C2B", OperationDirection.IN, CounterpartyType.PHONE)),
                version,
                RuntimeManifestStatus.VALID,
                createdAt,
                effectiveFrom,
                rules.size(),
                assignments.size(),
                memberships.size(),
                rules,
                assignments,
                memberships,
                List.<ManifestDiagnostic>of()
        );
        return new RuntimeManifest(
                UUID.randomUUID(),
                payload.schemaVersion(),
                payload.businessTimezone(),
                payload.operationTypes(),
                payload.version(),
                payload.status(),
                canonicalJson.checksum(payload),
                payload.createdAt(),
                payload.effectiveFrom(),
                payload.ruleCount(),
                payload.assignmentCount(),
                payload.membershipCount(),
                payload.rules(),
                payload.assignments(),
                payload.memberships(),
                payload.diagnostics(),
                payload
        );
    }

    private RuntimeCompiledRule compiledRule(UUID ruleId, String ruleCode) {
        return new RuntimeCompiledRule(
                ruleId,
                ruleCode,
                1,
                new RuntimeCompiledRule.Matcher(
                        List.of("SBP_C2B"),
                        OperationDirection.IN,
                        new RuleSelector<>(AttributeSelectorType.NONE, null),
                        LimitTargetType.PHONE
                ),
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                new BigDecimal("1000.00"),
                "template"
        );
    }

    private SnapshotIds insertSnapshot(String suffix) {
        UUID ruleId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID groupTypeId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        String ruleCode = "RULE_RUNTIME_" + suffix;

        jdbcTemplate.update("""
                insert into limit_management.limit_rules
                    (id, code, version, name, direction,
                     attribute_selector_type, attribute_selector_value, target_type,
                     metric, period, aggregation_scope, currency, interval_minutes,
                     limit_value, error_message_template,
                     status, created_at, updated_at, activated_at, disabled_at)
                values (?, ?, 1, ?, 'IN',
                        'NONE', null, 'PHONE',
                        'AMOUNT', 'DAY', 'OWNER', 'RUB', null,
                        1000.00, 'template',
                        'ACTIVE', now(), now(), now(), null)
                """, ruleId, ruleCode, ruleCode);
        jdbcTemplate.update("""
                insert into limit_management.limit_rule_operation_type (rule_id, operation_type_code)
                values (?, 'SBP_C2B')
                """, ruleId);
        jdbcTemplate.update("""
                insert into limit_management.limit_assignments
                    (id, rule_id, owner_type, owner_id, limit_mode,
                     valid_from, valid_to, enabled, created_at, updated_at)
                values (?, ?, 'MERCHANT', '502118', 'LIMITED',
                        ?, null, true, now(), now())
                """, assignmentId, ruleId, Timestamp.from(Instant.parse("2026-05-29T00:00:00Z")));
        jdbcTemplate.update("""
                insert into limit_management.merchant_group_types
                    (id, code, name, description, enabled, sort_order, created_at, updated_at)
                values (?, ?, ?, null, true, 10, now(), now())
                """, groupTypeId, "type-" + suffix, "Type " + suffix);
        jdbcTemplate.update("""
                insert into limit_management.merchant_groups
                    (id, type_id, code, name, description, enabled, created_at, updated_at)
                values (?, ?, ?, ?, null, true, now(), now())
                """, groupId, groupTypeId, "group-" + suffix, "Group " + suffix);
        jdbcTemplate.update("""
                insert into limit_management.merchant_group_memberships
                    (id, merchant_id, group_id, group_type_id, valid_from, valid_to, created_at, created_by)
                values (?, '502118', ?, ?, ?, null, now(), 'test')
                """, membershipId, groupId, groupTypeId, Timestamp.from(Instant.parse("2026-05-01T00:00:00Z")));

        return new SnapshotIds(ruleId, ruleCode, assignmentId, groupTypeId, groupId, membershipId);
    }

    private Integer childRows(String tableName, UUID manifestId) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from limit_management.%s
                where manifest_id = ?
                """.formatted(tableName), Integer.class, manifestId);
    }

    private record SnapshotIds(
            UUID ruleId,
            String ruleCode,
            UUID assignmentId,
            UUID groupTypeId,
            UUID groupId,
            UUID membershipId
    ) {
    }
}
