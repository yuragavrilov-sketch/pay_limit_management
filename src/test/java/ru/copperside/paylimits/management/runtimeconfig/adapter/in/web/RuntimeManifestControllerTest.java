package ru.copperside.paylimits.management.runtimeconfig.adapter.in.web;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;
import ru.copperside.paylimits.management.runtimeconfig.application.RuntimeManifestCanonicalJson;
import ru.copperside.paylimits.management.runtimeconfig.application.RuntimeManifestCompiler;
import ru.copperside.paylimits.management.runtimeconfig.application.port.out.RuntimeManifestRepository;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledRule;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestPayload;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestStatus;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeOperationType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "pay-limit-management.runtime-manifest.min-activation-lead-time=5m"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import({RuntimeManifestControllerTest.TestSupport.class,
        ru.copperside.paylimits.management.audit.OperatorHeaderTestConfig.class,
        ru.copperside.paylimits.management.audit.AuditWiringTestConfig.class})
class RuntimeManifestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeRepository repository;

    @Autowired
    private ru.copperside.paylimits.management.audit.AuditTestSupport.RecordingAuditEventRepository auditRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        repository.clear();
        LimitRule rule = repository.addActiveRule("RULE_SBP_PHONE_DAY");
        repository.addAssignment(rule.id(), rule.code());
        repository.addMembership("502118");
        auditRepository.clear();
    }

    @Test
    void compilesRuntimeManifest() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "effectiveFrom": "2026-05-29T10:15:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.manifestVersion").value(1))
                .andExpect(jsonPath("$.data.document.schemaVersion").value(2))
                .andExpect(jsonPath("$.data.status").value("VALID"))
                .andExpect(jsonPath("$.data.document.effectiveFrom").value("2026-05-29T10:15:00Z"))
                .andExpect(jsonPath("$.data.ruleCount").value(1))
                .andExpect(jsonPath("$.data.assignmentCount").value(1))
                .andExpect(jsonPath("$.data.membershipCount").value(1))
                .andExpect(jsonPath("$.data.document.rules[0].code").value("RULE_SBP_PHONE_DAY"))
                .andExpect(jsonPath("$.data.document.assignments[0].owner.level").value("MERCHANT"))
                .andExpect(jsonPath("$.data.document.assignments[0].owner.id").value("502118"))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void mapsLeadTimeViolationToBadRequestProblem() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "effectiveFrom": "2026-05-29T10:01:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RUNTIME_MANIFEST_LEAD_TIME_VIOLATION"));
    }

    @Test
    void mgtI10CompilingWithEffectiveFromInThePastReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "effectiveFrom": "2026-05-29T09:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RUNTIME_MANIFEST_LEAD_TIME_VIOLATION"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void getsEffectiveRuntimeManifest() throws Exception {
        RuntimeManifest manifest = repository.saveCompiledManifest(version -> repository.sampleManifest(
                version,
                Instant.parse("2026-05-29T10:15:00Z")
        ));

        mockMvc.perform(get("/internal/v1/limit-management/runtime-manifests/effective")
                        .queryParam("at", "2026-05-29T10:20:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(manifest.id().toString()))
                .andExpect(jsonPath("$.data.document.effectiveFrom").value("2026-05-29T10:15:00Z"));
    }

    @Test
    void returnsNotModifiedWhenIfNoneMatchMatchesEffectiveManifestChecksum() throws Exception {
        RuntimeManifest manifest = repository.saveCompiledManifest(version -> repository.sampleManifest(
                version,
                Instant.parse("2026-05-29T10:15:00Z")
        ));

        String etag = mockMvc.perform(get("/internal/v1/limit-management/runtime-manifests/effective")
                        .queryParam("at", "2026-05-29T10:20:00Z"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");

        assertThat(etag).isEqualTo("\"" + manifest.checksum() + "\"");

        mockMvc.perform(get("/internal/v1/limit-management/runtime-manifests/effective")
                        .queryParam("at", "2026-05-29T10:20:00Z")
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(""));
    }

    @Test
    void returnsOkWithBodyAndETagWhenIfNoneMatchDoesNotMatch() throws Exception {
        RuntimeManifest manifest = repository.saveCompiledManifest(version -> repository.sampleManifest(
                version,
                Instant.parse("2026-05-29T10:15:00Z")
        ));

        mockMvc.perform(get("/internal/v1/limit-management/runtime-manifests/effective")
                        .queryParam("at", "2026-05-29T10:20:00Z")
                        .header("If-None-Match", "\"sha256:stale\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + manifest.checksum() + "\""))
                .andExpect(jsonPath("$.data.id").value(manifest.id().toString()));
    }

    @Test
    void getsActiveRuntimeManifestAlias() throws Exception {
        RuntimeManifest manifest = repository.saveCompiledManifest(version -> repository.sampleManifest(
                version,
                Instant.parse("2026-05-29T10:15:00Z")
        ));

        mockMvc.perform(get("/internal/v1/limit-management/runtime-manifests/active")
                        .queryParam("at", "2026-05-29T10:20:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(manifest.id().toString()))
                .andExpect(jsonPath("$.data.document.effectiveFrom").value("2026-05-29T10:15:00Z"));
    }

    @Test
    void listsRuntimeManifestLifecycleHistory() throws Exception {
        repository.saveCompiledManifest(version -> repository.sampleManifest(version, Instant.parse("2026-05-29T10:15:00Z")));
        RuntimeManifest active = repository.saveCompiledManifest(version -> repository.sampleManifest(version, Instant.parse("2026-05-29T10:30:00Z")));
        RuntimeManifest scheduled = repository.saveCompiledManifest(version -> repository.sampleManifest(version, Instant.parse("2026-05-29T10:45:00Z")));

        mockMvc.perform(get("/internal/v1/limit-management/runtime-manifests")
                        .queryParam("at", "2026-05-29T10:35:00Z")
                        .queryParam("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(scheduled.id().toString()))
                .andExpect(jsonPath("$.data[0].lifecycleStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.data[1].id").value(active.id().toString()))
                .andExpect(jsonPath("$.data[1].lifecycleStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data[2].lifecycleStatus").value("SUPERSEDED"));
    }

    @Test
    void rollsBackRuntimeManifestIntoNewScheduledVersion() throws Exception {
        RuntimeManifest source = repository.saveCompiledManifest(version -> repository.sampleManifest(
                version,
                Instant.parse("2026-05-29T10:15:00Z")
        ));

        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests/{manifestId}/rollback", source.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "effectiveFrom": "2026-05-29T10:30:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.manifestVersion").value(2))
                .andExpect(jsonPath("$.data.document.effectiveFrom").value("2026-05-29T10:30:00Z"))
                .andExpect(jsonPath("$.data.document.rules[0].code").value("RULE_SBP_PHONE_DAY"));
    }

    @Test
    void mapsMissingEffectiveRuntimeManifestToNotFound() throws Exception {
        mockMvc.perform(get("/internal/v1/limit-management/runtime-manifests/effective")
                        .queryParam("at", "2026-05-29T10:00:00Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUNTIME_MANIFEST_NOT_FOUND"));
    }

    @Test
    void listsScheduledRuntimeManifestDescriptors() throws Exception {
        RuntimeManifest manifest = repository.saveCompiledManifest(version -> repository.sampleManifest(
                version,
                Instant.parse("2026-05-29T10:15:00Z")
        ));

        mockMvc.perform(get("/internal/v1/limit-management/runtime-manifests/scheduled")
                        .queryParam("after", "2026-05-29T10:00:00Z")
                        .queryParam("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(manifest.id().toString()))
                .andExpect(jsonPath("$.data[0].version").value(manifest.version()))
                .andExpect(jsonPath("$.data[0].checksum").value(manifest.checksum()))
                .andExpect(jsonPath("$.data[0].effectiveFrom").value("2026-05-29T10:15:00Z"))
                .andExpect(jsonPath("$.data[0].lifecycleStatus").doesNotExist());
    }

    @Test
    void getsRuntimeManifestById() throws Exception {
        RuntimeManifest manifest = repository.saveCompiledManifest(version -> repository.sampleManifest(
                version,
                Instant.parse("2026-05-29T10:15:00Z")
        ));

        mockMvc.perform(get("/internal/v1/limit-management/runtime-manifests/{manifestId}", manifest.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(manifest.id().toString()))
                .andExpect(jsonPath("$.data.document.rules[0].code").value("RULE_SBP_PHONE_DAY"));
    }

    // MGT-I-01: compiling a manifest records exactly one RUNTIME_MANIFEST / COMPILE audit event whose
    // entityId is the new manifest id and whose after-snapshot carries the descriptor (version/checksum).
    @Test
    void writesAuditEventOnManifestCompile() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "effectiveFrom": "2026-05-29T10:15:00Z" }
                                """))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(auditRepository.events()).hasSize(1);
        ru.copperside.paylimits.management.audit.domain.AuditEvent event = auditRepository.events().get(0);
        org.assertj.core.api.Assertions.assertThat(event.entityType()).isEqualTo("RUNTIME_MANIFEST");
        org.assertj.core.api.Assertions.assertThat(event.action()).isEqualTo("COMPILE");
        org.assertj.core.api.Assertions.assertThat(event.actorId())
                .isEqualTo(ru.copperside.paylimits.management.audit.OperatorHeaderTestConfig.OPERATOR_ID);
        org.assertj.core.api.Assertions.assertThat(event.beforeJson()).isNull();
        org.assertj.core.api.Assertions.assertThat(event.afterJson()).contains("checksum");
    }

    // MGT-I-14: compiling without X-Operator-Id is rejected and writes no audit event.
    @Test
    void rejectsManifestCompileWithoutOperatorIdAndWritesNoAudit() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .header("X-Operator-Id", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "effectiveFrom": "2026-05-29T10:15:00Z" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("OPERATOR_ID_REQUIRED"));

        org.assertj.core.api.Assertions.assertThat(auditRepository.events()).isEmpty();
    }

    // Task 4 (M2): compiling successfully records a pay_limit_management.manifest.compile timer
    // sample tagged operation=compile,result=success (spec §7).
    @Test
    void compilingRecordsCompileTimerWithSuccessResult() throws Exception {
        double before = compileTimerCount("compile", "success");

        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "effectiveFrom": "2026-05-29T10:15:00Z" }
                                """))
                .andExpect(status().isOk());

        assertThat(compileTimerCount("compile", "success")).isEqualTo(before + 1);
    }

    // Task 4 (M2): rollback shares the same timer, tagged operation=rollback.
    @Test
    void rollingBackRecordsCompileTimerWithRollbackOperationTag() throws Exception {
        RuntimeManifest source = repository.saveCompiledManifest(version -> repository.sampleManifest(
                version, Instant.parse("2026-05-29T10:15:00Z")));
        double before = compileTimerCount("rollback", "success");

        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests/{manifestId}/rollback", source.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "effectiveFrom": "2026-05-29T10:30:00Z" }
                                """))
                .andExpect(status().isOk());

        assertThat(compileTimerCount("rollback", "success")).isEqualTo(before + 1);
    }

    // Task 4 (M2): manifest-size and manifest-age gauges (spec §7) reflect live repository state.
    @Test
    void manifestSizeAndAgeGaugesReflectLatestCompiledManifest() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "effectiveFrom": "2026-05-29T10:15:00Z" }
                                """))
                .andExpect(status().isOk());

        assertThat(sizeGaugeValue("rules")).isEqualTo(1.0);
        assertThat(sizeGaugeValue("assignments")).isEqualTo(1.0);
        assertThat(sizeGaugeValue("memberships")).isEqualTo(1.0);
        assertThat(sizeGaugeValue("total")).isEqualTo(3.0);
        // Steady state right after compile: no config change postdates the manifest yet -> age is 0.
        // (The clock is fixed for this whole test class, so a positive-age scenario needs independent
        // control over "manifest created at" vs. "now" — covered by the Spring-free
        // RuntimeManifestMetricsTest, which drives that with its own Clock.)
        assertThat(ageGaugeValue()).isZero();
    }

    // Task 4 (M2): the new meters are visible via /actuator/metrics (existing ActuatorIntegrationTest
    // covers the endpoint itself; this asserts our specific meter names are registered).
    @Test
    void actuatorMetricsListsManifestMeterNames() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "effectiveFrom": "2026-05-29T10:15:00Z" }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names")
                        .value(org.hamcrest.Matchers.hasItem("pay_limit_management.manifest.compile")))
                .andExpect(jsonPath("$.names")
                        .value(org.hamcrest.Matchers.hasItem("pay_limit_management.manifest.size")))
                .andExpect(jsonPath("$.names")
                        .value(org.hamcrest.Matchers.hasItem("pay_limit_management.manifest.unpublished_change_age_seconds")));
    }

    private double compileTimerCount(String operation, String result) {
        io.micrometer.core.instrument.Timer timer = meterRegistry.find("pay_limit_management.manifest.compile")
                .tag("operation", operation)
                .tag("result", result)
                .timer();
        return timer == null ? 0.0 : timer.count();
    }

    private double sizeGaugeValue(String component) {
        return meterRegistry.get("pay_limit_management.manifest.size")
                .tag("component", component)
                .gauge()
                .value();
    }

    private double ageGaugeValue() {
        return meterRegistry.get("pay_limit_management.manifest.unpublished_change_age_seconds")
                .gauge()
                .value();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSupport {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-05-29T10:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        FakeRepository fakeRuntimeManifestRepository() {
            return new FakeRepository();
        }

        @Bean
        ru.copperside.paylimits.management.common.invariant.port.TransactionRunner transactionRunner() {
            return new ru.copperside.paylimits.management.common.invariant.InvariantTestSupport.PassThroughTransactionRunner();
        }

        @Bean("testRuntimeManifestCompiler")
        @Primary
        RuntimeManifestCompiler runtimeManifestCompiler(
                FakeRepository repository,
                Clock clock,
                ru.copperside.paylimits.management.common.invariant.port.TransactionRunner transactionRunner,
                ru.copperside.paylimits.management.audit.application.AuditRecorder auditRecorder
        ) {
            return new RuntimeManifestCompiler(
                    repository, clock, java.time.Duration.ofMinutes(5), "Europe/Moscow", transactionRunner, auditRecorder);
        }
    }

    static class FakeRepository implements RuntimeManifestRepository {

        private final RuntimeManifestCanonicalJson canonicalJson = new RuntimeManifestCanonicalJson();
        final List<LimitRule> rules = new ArrayList<>();
        final List<RuntimeCompiledAssignment> assignments = new ArrayList<>();
        final List<RuntimeMerchantGroupMembership> memberships = new ArrayList<>();
        final List<RuntimeManifest> manifests = new ArrayList<>();
        Instant latestConfigChangeAt;

        FakeRepository clear() {
            rules.clear();
            assignments.clear();
            memberships.clear();
            manifests.clear();
            latestConfigChangeAt = null;
            return this;
        }

        LimitRule addActiveRule(String code) {
            LimitRule rule = new LimitRule(
                    UUID.randomUUID(),
                    code,
                    1,
                    code,
                    Set.of("SBP_C2B"),
                    OperationDirection.IN,
                    new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                    LimitTargetType.PHONE,
                    new BigDecimal("1000.00"),
                    "template",
                    new RuleSelector<>(AttributeSelectorType.NONE, null),
                    RuleStatus.ACTIVE,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    null
            );
            rules.add(rule);
            return rule;
        }

        RuntimeCompiledAssignment addAssignment(UUID ruleId, String ruleCode) {
            RuntimeCompiledAssignment assignment = new RuntimeCompiledAssignment(
                    UUID.randomUUID(),
                    ruleId,
                    ruleCode,
                    AssignmentOwnerType.MERCHANT,
                    "502118",
                    LimitMode.LIMITED,
                    Instant.parse("2026-05-29T00:00:00Z"),
                    null
            );
            assignments.add(assignment);
            return assignment;
        }

        RuntimeMerchantGroupMembership addMembership(String merchantId) {
            RuntimeMerchantGroupMembership membership = new RuntimeMerchantGroupMembership(
                    UUID.randomUUID(),
                    merchantId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    Instant.parse("2026-05-01T00:00:00Z"),
                    null
            );
            memberships.add(membership);
            return membership;
        }

        RuntimeManifest sampleManifest(int version, Instant effectiveFrom) {
            List<RuntimeCompiledRule> compiledRules = rules.stream()
                    .sorted(Comparator.comparing(LimitRule::code))
                    .map(RuntimeManifestCompiler::compileRule)
                    .toList();
            RuntimeManifestPayload payload = new RuntimeManifestPayload(
                    2,
                    "Europe/Moscow",
                    List.of(),
                    version,
                    RuntimeManifestStatus.VALID,
                    Instant.parse("2026-05-29T10:00:00Z"),
                    effectiveFrom,
                    compiledRules.size(),
                    assignments.size(),
                    memberships.size(),
                    compiledRules,
                    List.copyOf(assignments),
                    List.copyOf(memberships),
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

        @Override
        public List<LimitRule> listActiveRulesForCompilation() {
            return List.copyOf(rules);
        }

        @Override
        public List<RuntimeCompiledAssignment> listEnabledAssignmentsForCompilation() {
            return List.copyOf(assignments);
        }

        @Override
        public List<RuntimeMerchantGroupMembership> listMembershipsForCompilation() {
            return List.copyOf(memberships);
        }

        @Override
        public List<RuntimeOperationType> listOperationTypesForManifest() {
            return List.of();
        }

        @Override
        public RuntimeManifest saveCompiledManifest(CompiledRuntimeManifestFactory factory) {
            RuntimeManifest manifest = factory.create(manifests.size() + 1);
            manifests.add(manifest);
            return manifest;
        }

        @Override
        public Optional<RuntimeManifest> findManifest(UUID id) {
            return manifests.stream().filter(manifest -> manifest.id().equals(id)).findFirst();
        }

        @Override
        public Optional<RuntimeManifest> findEffectiveManifest(Instant at) {
            return manifests.stream()
                    .filter(manifest -> !manifest.effectiveFrom().isAfter(at))
                    .max(Comparator.comparingInt(RuntimeManifest::version));
        }

        @Override
        public List<RuntimeManifestDescriptor> listScheduledManifests(Instant after, int limit) {
            return manifests.stream()
                    .filter(manifest -> manifest.effectiveFrom().isAfter(after))
                    .sorted(Comparator.comparing(RuntimeManifest::effectiveFrom).thenComparingInt(RuntimeManifest::version))
                    .limit(limit)
                    .map(manifest -> new RuntimeManifestDescriptor(
                            manifest.id(),
                            manifest.version(),
                            manifest.checksum(),
                            manifest.createdAt(),
                            manifest.effectiveFrom(),
                            null
                    ))
                    .toList();
        }

        @Override
        public List<RuntimeManifestDescriptor> listManifests(int limit) {
            return manifests.stream()
                    .sorted(Comparator.comparingInt(RuntimeManifest::version).reversed())
                    .limit(limit)
                    .map(manifest -> new RuntimeManifestDescriptor(
                            manifest.id(),
                            manifest.version(),
                            manifest.checksum(),
                            manifest.createdAt(),
                            manifest.effectiveFrom(),
                            null
                    ))
                    .toList();
        }

        @Override
        public java.util.Optional<ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestSizeSnapshot> findLatestManifestSize() {
            return manifests.stream()
                    .max(Comparator.comparingInt(RuntimeManifest::version))
                    .map(manifest -> new ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestSizeSnapshot(
                            manifest.ruleCount(), manifest.assignmentCount(), manifest.membershipCount(), manifest.createdAt()));
        }

        @Override
        public java.util.Optional<Instant> findLatestConfigChangeAt() {
            return java.util.Optional.ofNullable(latestConfigChangeAt);
        }
    }
}
