package ru.copperside.paylimits.management.limitrule.adapter.in.web;

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
import ru.copperside.paylimits.management.limitrule.application.RuleManifestCompiler;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CompiledRule;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnosticSeverity;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifest;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestProblemException;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestStatus;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestPayload;
import ru.copperside.paylimits.management.limitrule.application.port.out.RuleManifestRepository;

import java.math.BigDecimal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import({RuleManifestControllerTest.TestSupport.class,
        ru.copperside.paylimits.management.audit.OperatorHeaderTestConfig.class})
class RuleManifestControllerTest {

    private static final Instant NOW = Instant.parse("2026-05-28T09:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler.reset();
    }

    @Test
    void compilesRuleManifest() throws Exception {
        RuleManifest manifest = fakeManifest();
        compiler.willCompile(manifest);

        mockMvc.perform(post("/internal/v1/limit-management/rule-manifests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(manifest.id().toString()))
                .andExpect(jsonPath("$.data.version").value(manifest.version()))
                .andExpect(jsonPath("$.data.status").value("VALID"))
                .andExpect(jsonPath("$.data.checksum").value(manifest.checksum()))
                .andExpect(jsonPath("$.data.ruleCount").value(manifest.ruleCount()))
                .andExpect(jsonPath("$.data.rules[0].code").value("RULE_SBP_PHONE_DAY"))
                .andExpect(jsonPath("$.data.rules[0].matcher.direction").value("IN"))
                .andExpect(jsonPath("$.data.diagnostics").isArray())
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void readsLatestRuleManifest() throws Exception {
        RuleManifest manifest = fakeManifest();
        compiler.willReturnLatest(manifest);

        mockMvc.perform(get("/internal/v1/limit-management/rule-manifests/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(manifest.id().toString()))
                .andExpect(jsonPath("$.data.version").value(manifest.version()));
    }

    @Test
    void readsRuleManifestById() throws Exception {
        RuleManifest manifest = fakeManifest();
        compiler.willReturnManifest(manifest.id(), manifest);

        mockMvc.perform(get("/internal/v1/limit-management/rule-manifests/{manifestId}", manifest.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(manifest.id().toString()))
                .andExpect(jsonPath("$.data.rules[0].ruleId").value(manifest.rules().get(0).ruleId().toString()));
    }

    @Test
    void mapsMissingManifestToNotFound() throws Exception {
        compiler.willGetLatestThrow(new RuleManifestProblemException(
                "RULE_MANIFEST_NOT_FOUND",
                "Rule manifest not found"
        ));

        mockMvc.perform(get("/internal/v1/limit-management/rule-manifests/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RULE_MANIFEST_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Rule manifest not found"));
    }

    @Test
    void mapsCompileConflictToConflict() throws Exception {
        compiler.willCompileThrow(new RuleManifestProblemException(
                "RULE_MANIFEST_CONFLICT",
                "Active rules cannot be compiled into a manifest",
                List.of(
                        new ManifestDiagnostic(
                                "MANIFEST_DUPLICATE_RULE",
                                ManifestDiagnosticSeverity.ERROR,
                                "Two active rules compile to the same matcher and measure",
                                List.of(UUID.fromString("0db59f6a-7f8c-45d6-b6a7-cc1fcb397c6e"), UUID.fromString("4cc50b83-08a1-4e79-9768-9f9f692c13b1")),
                                "rules"
                        )
                )
        ));

        mockMvc.perform(post("/internal/v1/limit-management/rule-manifests"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RULE_MANIFEST_CONFLICT"))
                .andExpect(jsonPath("$.error.details.diagnostics[0].code").value("MANIFEST_DUPLICATE_RULE"));
    }

    @Test
    void mapsValidationErrorToBadRequest() throws Exception {
        compiler.willCompileThrow(new RuleManifestProblemException(
                "VALIDATION_ERROR",
                "Active rules payload is invalid"
        ));

        mockMvc.perform(post("/internal/v1/limit-management/rule-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Active rules payload is invalid"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSupport {

        @Bean
        @Primary
        FakeCompiler fakeManifestCompiler() {
            return new FakeCompiler();
        }
    }

    static class FakeCompiler extends RuleManifestCompiler {
        private RuleManifest compileManifest;
        private RuntimeException compileError;
        private RuleManifest latestManifest;
        private RuntimeException latestError;
        private final Map<UUID, RuleManifest> manifestsById = new java.util.HashMap<>();

        FakeCompiler() {
            super(new EmptyManifestRepository(), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        void reset() {
            compileManifest = null;
            compileError = null;
            latestManifest = null;
            latestError = null;
            manifestsById.clear();
        }

        void willCompile(RuleManifest manifest) {
            compileManifest = manifest;
        }

        void willCompileThrow(RuntimeException error) {
            compileError = error;
        }

        void willGetLatestThrow(RuntimeException error) {
            latestError = error;
        }

        void willReturnLatest(RuleManifest manifest) {
            latestManifest = manifest;
        }

        void willReturnManifest(UUID id, RuleManifest manifest) {
            manifestsById.put(id, manifest);
        }

        @Override
        public RuleManifest compile() {
            if (compileError != null) {
                throw compileError;
            }
            if (compileManifest == null) {
                throw new IllegalStateException("No mocked manifest configured for compile");
            }
            return compileManifest;
        }

        @Override
        public RuleManifest getLatest() {
            if (latestError != null) {
                throw latestError;
            }
            if (latestManifest == null) {
                throw new RuleManifestProblemException("RULE_MANIFEST_NOT_FOUND", "Rule manifest not found");
            }
            return latestManifest;
        }

        @Override
        public RuleManifest getManifest(UUID id) {
            RuleManifest manifest = manifestsById.get(id);
            if (manifest == null) {
                throw new RuleManifestProblemException("RULE_MANIFEST_NOT_FOUND", "Rule manifest not found");
            }
            return manifest;
        }
    }

    static class EmptyManifestRepository implements RuleManifestRepository {

        @Override
        public java.util.List<ru.copperside.paylimits.management.limitrule.domain.LimitRule> listActiveRulesForCompilation() {
            return List.of();
        }

        @Override
        public ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries getRuleDictionaries() {
            throw new UnsupportedOperationException("Not used in tests");
        }

        @Override
        public RuleManifest saveCompiledManifest(CompiledManifestFactory factory) {
            throw new UnsupportedOperationException("Not used in tests");
        }

        @Override
        public java.util.Optional<RuleManifest> findLatestManifest() {
            throw new UnsupportedOperationException("Not used in tests");
        }

        @Override
        public java.util.Optional<RuleManifest> findManifest(UUID id) {
            throw new UnsupportedOperationException("Not used in tests");
        }
    }

    private static RuleManifest fakeManifest() {
        CompiledRule compiled = new CompiledRule(
                UUID.fromString("0db59f6a-7f8c-45d6-b6a7-cc1fcb397c6e"),
                "RULE_SBP_PHONE_DAY",
                1,
                new CompiledRule.Matcher(
                        List.of("SBP_C2B"),
                        OperationDirection.IN,
                        new RuleSelector<>(AttributeSelectorType.NONE, null),
                        LimitTargetType.PHONE
                ),
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                new BigDecimal("1000.00"),
                "template"
        );
        RuleManifestPayload payload = new RuleManifestPayload(
                1,
                RuleManifestStatus.VALID,
                1,
                NOW,
                List.of(compiled),
                new ArrayList<>()
        );
        return new RuleManifest(
                UUID.randomUUID(),
                1,
                RuleManifestStatus.VALID,
                "sha256:8e4fb0f6d1f6d8a8e3c2f0a0c6a2d2f0f25c3adf2846d8fd11f6d8a809122001",
                1,
                NOW,
                payload.rules(),
                payload.diagnostics(),
                payload
        );
    }
}
