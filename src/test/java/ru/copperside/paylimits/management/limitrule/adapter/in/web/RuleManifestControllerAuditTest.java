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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.copperside.paylimits.management.audit.AuditTestSupport;
import ru.copperside.paylimits.management.audit.AuditWiringTestConfig;
import ru.copperside.paylimits.management.audit.OperatorHeaderTestConfig;
import ru.copperside.paylimits.management.audit.application.AuditRecorder;
import ru.copperside.paylimits.management.audit.domain.AuditEvent;
import ru.copperside.paylimits.management.common.invariant.InvariantTestSupport;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;
import ru.copperside.paylimits.management.limitrule.application.RuleManifestCompiler;
import ru.copperside.paylimits.management.limitrule.application.port.out.RuleManifestRepository;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.DictionaryItem;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifest;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Closes the spec §6 audit gap on the legacy v1 rule-manifest compile endpoint
 * ({@code POST /internal/v1/limit-management/rule-manifests}). Wires a REAL {@link RuleManifestCompiler}
 * (unlike {@link RuleManifestControllerTest}, which stubs the compiler entirely and therefore never
 * exercises {@link RuleManifestCompiler#compile()}'s own audit-recording logic) against a fake
 * {@link RuleManifestRepository}, mirroring the pattern used for the runtime-manifest audit coverage.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import({RuleManifestControllerAuditTest.TestSupport.class,
        OperatorHeaderTestConfig.class,
        AuditWiringTestConfig.class})
class RuleManifestControllerAuditTest {

    private static final Instant NOW = Instant.parse("2026-05-28T09:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeRepository repository;

    @Autowired
    private AuditTestSupport.RecordingAuditEventRepository auditRepository;

    @BeforeEach
    void setUp() {
        repository.clear();
        repository.addActiveRule("RULE_SBP_PHONE_DAY", Set.of("SBP_C2B"), OperationDirection.IN,
                new RuleSelector<>(AttributeSelectorType.NONE, null));
        auditRepository.clear();
    }

    // MGT-I-01 style coverage, v1 endpoint: compiling a rule manifest records exactly one
    // RULE_MANIFEST / COMPILE audit event whose entityId is the new manifest id.
    @Test
    void writesAuditEventOnRuleManifestCompile() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/rule-manifests"))
                .andExpect(status().isOk());

        assertThat(auditRepository.events()).hasSize(1);
        AuditEvent event = auditRepository.events().get(0);
        assertThat(event.entityType()).isEqualTo("RULE_MANIFEST");
        assertThat(event.action()).isEqualTo("COMPILE");
        assertThat(event.actorId()).isEqualTo(OperatorHeaderTestConfig.OPERATOR_ID);
        assertThat(event.beforeJson()).isNull();
        assertThat(event.afterJson()).contains("checksum");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSupport {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        FakeRepository fakeRuleManifestRepository() {
            return new FakeRepository();
        }

        @Bean
        TransactionRunner transactionRunner() {
            return new InvariantTestSupport.PassThroughTransactionRunner();
        }

        @Bean("testRuleManifestCompiler")
        @Primary
        RuleManifestCompiler ruleManifestCompiler(
                FakeRepository repository,
                Clock clock,
                TransactionRunner transactionRunner,
                AuditRecorder auditRecorder
        ) {
            return new RuleManifestCompiler(repository, clock, transactionRunner, auditRecorder);
        }
    }

    static class FakeRepository implements RuleManifestRepository {

        final List<LimitRule> activeRules = new ArrayList<>();
        final List<RuleManifest> saved = new ArrayList<>();
        int nextVersion = 1;

        FakeRepository clear() {
            activeRules.clear();
            saved.clear();
            nextVersion = 1;
            return this;
        }

        LimitRule addActiveRule(
                String code,
                Set<String> operationTypes,
                OperationDirection direction,
                RuleSelector<AttributeSelectorType> attributeSelector
        ) {
            LimitRule rule = new LimitRule(
                    UUID.randomUUID(),
                    code,
                    1,
                    code,
                    operationTypes,
                    direction,
                    new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                    LimitTargetType.PHONE,
                    new BigDecimal("1000.00"),
                    "template",
                    attributeSelector,
                    RuleStatus.ACTIVE,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    null
            );
            activeRules.add(rule);
            return rule;
        }

        @Override
        public List<LimitRule> listActiveRulesForCompilation() {
            return List.copyOf(activeRules);
        }

        @Override
        public RuleDictionaries getRuleDictionaries() {
            return new RuleDictionaries(
                    List.of(item("SBP", true)),
                    List.of(operationType("SBP_C2B", "SBP", OperationDirection.IN, true)),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
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
        public RuleManifest saveCompiledManifest(CompiledManifestFactory factory) {
            RuleManifest manifest = factory.create(nextVersion++, List.copyOf(activeRules), getRuleDictionaries());
            saved.add(manifest);
            return manifest;
        }

        @Override
        public Optional<RuleManifest> findLatestManifest() {
            return saved.stream().reduce((left, right) -> right);
        }

        @Override
        public Optional<RuleManifest> findManifest(UUID id) {
            return saved.stream().filter(manifest -> manifest.id().equals(id)).findFirst();
        }

        private static DictionaryItem item(String code, boolean enabled) {
            return new DictionaryItem(code, code, enabled, 10, Instant.EPOCH, Instant.EPOCH);
        }

        private static OperationType operationType(String code, String familyCode, OperationDirection direction, boolean enabled) {
            return new OperationType(UUID.randomUUID(), code, code, familyCode, direction, CounterpartyType.CARD, enabled, 10, Instant.EPOCH, Instant.EPOCH);
        }
    }
}
