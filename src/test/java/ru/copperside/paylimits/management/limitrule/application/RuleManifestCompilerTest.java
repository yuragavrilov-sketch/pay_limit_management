package ru.copperside.paylimits.management.limitrule.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.copperside.paylimits.management.limitrule.application.port.out.RuleManifestRepository;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CompiledRule;
import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.DictionaryItem;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestDiagnosticsDetails;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifest;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestProblemException;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleManifestCompilerTest {

    private static final Instant NOW = Instant.parse("2026-05-28T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private FakeManifestRepository repository;
    private RuleManifestCompiler compiler;
    private ru.copperside.paylimits.management.audit.AuditTestSupport.RecordingAuditEventRepository auditRepository;

    @BeforeEach
    void setUp() {
        repository = new FakeManifestRepository();
        auditRepository = new ru.copperside.paylimits.management.audit.AuditTestSupport.RecordingAuditEventRepository();
        compiler = new RuleManifestCompiler(
                repository,
                CLOCK,
                new ru.copperside.paylimits.management.common.invariant.InvariantTestSupport.PassThroughTransactionRunner(),
                ru.copperside.paylimits.management.audit.AuditTestSupport.recorder(auditRepository, CLOCK));
    }

    @Test
    void compilesActiveRulesIntoDeterministicManifest() {
        LimitRule second = repository.addActiveRule("RULE_Z", Set.of("ECOM"), OperationDirection.IN, noneSelector());
        LimitRule first = repository.addActiveRule("RULE_A", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());

        RuleManifest manifest = compiler.compile();

        assertThat(manifest.id()).isNotNull();
        assertThat(manifest.version()).isEqualTo(1);
        assertThat(manifest.status()).isEqualTo(RuleManifestStatus.VALID);
        assertThat(manifest.checksum()).startsWith("sha256:");
        assertThat(manifest.createdAt()).isEqualTo(NOW);
        assertThat(manifest.ruleCount()).isEqualTo(2);
        assertThat(manifest.rules()).extracting(CompiledRule::ruleId).containsExactly(first.id(), second.id());
        assertThat(manifest.rules().getFirst().matcher().operationTypes()).containsExactly("SBP_C2B");
        assertThat(manifest.diagnostics()).isEmpty();
        assertThat(manifest.payload()).isNotNull();
        assertThat(manifest.payload().version()).isEqualTo(manifest.version());
        assertThat(manifest.payload().status()).isEqualTo(manifest.status());
        assertThat(manifest.payload().ruleCount()).isEqualTo(manifest.ruleCount());
        assertThat(manifest.payload().createdAt()).isEqualTo(manifest.createdAt());
        assertThat(manifest.payload().rules()).isEqualTo(manifest.rules());
        assertThat(manifest.payload().diagnostics()).isEqualTo(manifest.diagnostics());
        assertThat(repository.saved).containsExactly(manifest);
    }

    // Spec §6: every mutating operation is audited, including the legacy v1 rule-manifest compile.
    @Test
    void writesAuditEventOnManifestCompile() {
        repository.addActiveRule("RULE_A", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());

        RuleManifest manifest = compiler.compile();

        assertThat(auditRepository.events()).hasSize(1);
        var event = auditRepository.events().get(0);
        assertThat(event.entityType()).isEqualTo("RULE_MANIFEST");
        assertThat(event.entityId()).isEqualTo(manifest.id().toString());
        assertThat(event.action()).isEqualTo("COMPILE");
        assertThat(event.beforeJson()).isNull();
        assertThat(event.afterJson()).contains(manifest.checksum());
    }

    @Test
    void writesNoAuditEventWhenCompileFailsOnConflict() {
        repository.addActiveRule("RULE_A", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());
        repository.addActiveRule("RULE_B", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());

        assertThatThrownBy(() -> compiler.compile()).isInstanceOf(RuleManifestProblemException.class);

        assertThat(auditRepository.events()).isEmpty();
    }

    @Test
    void compilesUsingRepositoryOwnedSnapshot() {
        LimitRule rule = repository.addActiveRule("RULE_A", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());
        repository.failDirectSnapshotReads = true;

        RuleManifest manifest = compiler.compile();

        assertThat(manifest.rules()).extracting(CompiledRule::ruleId).containsExactly(rule.id());
        assertThat(repository.snapshotCallbackUsed).isTrue();
        assertThat(repository.saved).containsExactly(manifest);
    }

    @Test
    void keepsChecksumStableForSameVersionClockAndRules() {
        repository.addActiveRule("RULE_A", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());

        RuleManifest first = compiler.compile();
        repository.saved.clear();
        repository.nextVersion = 1;
        RuleManifest second = compiler.compile();

        assertThat(second.checksum()).isEqualTo(first.checksum());
    }

    @Test
    void keepsChecksumStableWhenOperationTypeSetIsReordered() {
        UUID ruleId = UUID.randomUUID();
        repository.activeRules.add(activeRule(ruleId, "RULE_A", orderedSet("SBP_C2B", "SBP_B2B_IN")));
        RuleManifest first = compiler.compile();

        repository.clearRules();
        repository.saved.clear();
        repository.nextVersion = 1;
        repository.activeRules.add(activeRule(ruleId, "RULE_A", orderedSet("SBP_B2B_IN", "SBP_C2B")));
        RuleManifest second = compiler.compile();

        assertThat(second.checksum()).isEqualTo(first.checksum());
    }

    @Test
    void rejectsDuplicateMatcherAndMeasure() {
        repository.addActiveRule("RULE_A", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());
        repository.addActiveRule("RULE_B", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .hasMessageContaining("RULE_MANIFEST_CONFLICT")
                .satisfies(error -> {
                    RuleManifestProblemException problem = (RuleManifestProblemException) error;
                    assertThat(problem.details()).isInstanceOf(RuleManifestDiagnosticsDetails.class);
                    assertThat(problem.diagnostics())
                            .extracting(diagnostic -> diagnostic.code())
                            .contains("MANIFEST_DUPLICATE_RULE");
                });

        assertThat(repository.saved).isEmpty();
        assertThat(repository.snapshotCallbackUsed).isTrue();
    }

    @Test
    void rejectsOverlappingOperationTypeSetsForSameMatcherAndMeasure() {
        repository.addActiveRule("RULE_WIDE", orderedSet("SBP_C2B", "SBP_B2B_IN"), OperationDirection.IN, noneSelector());
        repository.addActiveRule("RULE_NARROW", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .hasMessageContaining("RULE_MANIFEST_CONFLICT")
                .satisfies(error -> assertThat(((RuleManifestProblemException) error).diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MANIFEST_OVERLAPPING_OPERATION_SCOPE"));
    }

    @Test
    void allowsDisjointOperationTypeSetsForSameMatcherAndMeasure() {
        repository.addActiveRule("RULE_A", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());
        repository.addActiveRule("RULE_B", Set.of("SBP_B2B_IN"), OperationDirection.IN, noneSelector());

        RuleManifest manifest = compiler.compile();

        assertThat(manifest.ruleCount()).isEqualTo(2);
        assertThat(repository.saved).containsExactly(manifest);
    }

    @Test
    void rejectsDisabledDictionaryValue() {
        repository.paymentSystemsEnabled = false;
        repository.addActiveRule("RULE_MIR", Set.of("SBP_C2B"), OperationDirection.IN,
                new RuleSelector<>(AttributeSelectorType.PAYMENT_SYSTEM, "MIR"));

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .satisfies(error -> assertThat(((RuleManifestProblemException) error).diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MANIFEST_DICTIONARY_VALUE_DISABLED"));
    }

    @Test
    void rejectsDirectionIncompatibleWithOperationType() {
        repository.addActiveRule("RULE_BAD_DIRECTION", Set.of("SBP_C2B"), OperationDirection.OUT, noneSelector());

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .satisfies(error -> assertThat(((RuleManifestProblemException) error).diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MANIFEST_DIRECTION_CONFLICT"));
    }

    @Test
    void rejectsUnknownOperationType() {
        repository.addActiveRule("RULE_UNKNOWN", Set.of("UNKNOWN_TYPE"), OperationDirection.IN, noneSelector());

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .hasMessageContaining("RULE_MANIFEST_CONFLICT")
                .satisfies(error -> assertThat(((RuleManifestProblemException) error).diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MANIFEST_DICTIONARY_VALUE_MISSING"));

        assertThat(repository.saved).isEmpty();
    }

    @Test
    void rejectsRuleWithoutOperationTypes() {
        repository.addActiveRule("RULE_EMPTY", Set.of(), OperationDirection.IN, noneSelector());

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .hasMessageContaining("RULE_MANIFEST_CONFLICT")
                .satisfies(error -> assertThat(((RuleManifestProblemException) error).diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MANIFEST_INVALID_RULE_DEFINITION"));

        assertThat(repository.saved).isEmpty();
    }

    @Test
    void delegatesLatestManifestLookup() {
        repository.addActiveRule("RULE_A", Set.of("SBP_C2B"), OperationDirection.IN, noneSelector());
        RuleManifest manifest = compiler.compile();

        assertThat(compiler.getLatest()).isEqualTo(manifest);
    }

    @Test
    void rejectsMissingManifestLookup() {
        assertThatThrownBy(() -> compiler.getManifest(UUID.randomUUID()))
                .isInstanceOf(RuleManifestProblemException.class)
                .hasMessageContaining("RULE_MANIFEST_NOT_FOUND");
    }

    private static Set<String> orderedSet(String... codes) {
        return new java.util.LinkedHashSet<>(Arrays.asList(codes));
    }

    private static LimitRule activeRule(UUID id, String code, Set<String> operationTypes) {
        return new LimitRule(
                id,
                code,
                1,
                code,
                operationTypes,
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
    }

    private static RuleSelector<AttributeSelectorType> noneSelector() {
        return new RuleSelector<>(AttributeSelectorType.NONE, null);
    }

    static class FakeManifestRepository implements RuleManifestRepository {
        final List<LimitRule> activeRules = new ArrayList<>();
        final List<RuleManifest> saved = new ArrayList<>();
        int nextVersion = 1;
        boolean paymentSystemsEnabled = true;
        boolean failDirectSnapshotReads;
        boolean snapshotCallbackUsed;

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

        void clearRules() {
            activeRules.clear();
        }

        @Override
        public List<LimitRule> listActiveRulesForCompilation() {
            if (failDirectSnapshotReads) {
                throw new IllegalStateException("Compiler must use repository-owned snapshot");
            }
            return List.copyOf(activeRules);
        }

        @Override
        public RuleDictionaries getRuleDictionaries() {
            if (failDirectSnapshotReads) {
                throw new IllegalStateException("Compiler must use repository-owned snapshot");
            }
            return new RuleDictionaries(
                    List.of(item("SBP", true), item("CARD", true)),
                    List.of(
                            operationType("SBP_C2B", "SBP", OperationDirection.IN, true),
                            operationType("SBP_B2C", "SBP", OperationDirection.OUT, true),
                            operationType("SBP_B2B_IN", "SBP", OperationDirection.IN, true),
                            operationType("ECOM", "CARD", OperationDirection.IN, true)
                    ),
                    List.of(item("MIR", paymentSystemsEnabled)),
                    List.of(item("RU", true)),
                    List.of(item("TKB", true)),
                    List.of(item("220220", true)),
                    List.of(item("DEBIT", true)),
                    List.of(item("GOLD", true)),
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
            snapshotCallbackUsed = true;
            RuleManifest manifest = factory.create(nextVersion++, List.copyOf(activeRules), getRuleDictionariesForSnapshot());
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

        private RuleDictionaries getRuleDictionariesForSnapshot() {
            boolean previous = failDirectSnapshotReads;
            failDirectSnapshotReads = false;
            try {
                return getRuleDictionaries();
            } finally {
                failDirectSnapshotReads = previous;
            }
        }
    }
}
