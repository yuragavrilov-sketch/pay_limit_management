package ru.copperside.paylimits.management.limitrule.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestProblemException;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestStatus;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleManifestCompilerTest {

    private static final Instant NOW = Instant.parse("2026-05-28T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private FakeManifestRepository repository;
    private RuleManifestCompiler compiler;

    @BeforeEach
    void setUp() {
        repository = new FakeManifestRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        compiler = new RuleManifestCompiler(repository, CLOCK, objectMapper);
    }

    @Test
    void compilesActiveRulesIntoDeterministicManifest() {
        LimitRule second = repository.addActiveRule("RULE_Z", familySelector("CARD"), OperationDirection.IN, noneSelector());
        LimitRule first = repository.addActiveRule("RULE_A", typeSelector("SBP_C2B"), OperationDirection.IN, noneSelector());

        RuleManifest manifest = compiler.compile();

        assertThat(manifest.id()).isNotNull();
        assertThat(manifest.version()).isEqualTo(1);
        assertThat(manifest.status()).isEqualTo(RuleManifestStatus.VALID);
        assertThat(manifest.checksum()).startsWith("sha256:");
        assertThat(manifest.createdAt()).isEqualTo(NOW);
        assertThat(manifest.ruleCount()).isEqualTo(2);
        assertThat(manifest.rules()).extracting(CompiledRule::ruleId).containsExactly(first.id(), second.id());
        assertThat(manifest.rules().getFirst().matcher().operation().type()).isEqualTo(OperationSelectorType.TYPE);
        assertThat(manifest.diagnostics()).isEmpty();
        assertThat(repository.saved).containsExactly(manifest);
    }

    @Test
    void keepsChecksumStableForSameVersionClockAndRules() {
        repository.addActiveRule("RULE_A", typeSelector("SBP_C2B"), OperationDirection.IN, noneSelector());

        RuleManifest first = compiler.compile();
        repository.saved.clear();
        repository.nextVersion = 1;
        RuleManifest second = compiler.compile();

        assertThat(second.checksum()).isEqualTo(first.checksum());
    }

    @Test
    void rejectsDuplicateMatcherAndMeasure() {
        repository.addActiveRule("RULE_A", familySelector("SBP"), OperationDirection.IN, noneSelector());
        repository.addActiveRule("RULE_B", familySelector("SBP"), OperationDirection.IN, noneSelector());

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .hasMessageContaining("RULE_MANIFEST_CONFLICT")
                .satisfies(error -> assertThat(((RuleManifestProblemException) error).diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MANIFEST_DUPLICATE_RULE"));

        assertThat(repository.saved).isEmpty();
    }

    @Test
    void rejectsAmbiguousAnyAndFamilyOperationScopes() {
        repository.addActiveRule("RULE_ANY", anySelector(), OperationDirection.IN, noneSelector());
        repository.addActiveRule("RULE_FAMILY", familySelector("SBP"), OperationDirection.IN, noneSelector());

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .hasMessageContaining("RULE_MANIFEST_CONFLICT")
                .satisfies(error -> assertThat(((RuleManifestProblemException) error).diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MANIFEST_OVERLAPPING_OPERATION_SCOPE"));
    }

    @Test
    void rejectsFamilyAndTypeOperationScopesFromSameFamily() {
        repository.addActiveRule("RULE_FAMILY", familySelector("SBP"), OperationDirection.IN, noneSelector());
        repository.addActiveRule("RULE_TYPE", typeSelector("SBP_C2B"), OperationDirection.IN, noneSelector());

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .satisfies(error -> assertThat(((RuleManifestProblemException) error).diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MANIFEST_OVERLAPPING_OPERATION_SCOPE"));
    }

    @Test
    void rejectsDisabledDictionaryValue() {
        repository.paymentSystemsEnabled = false;
        repository.addActiveRule("RULE_MIR", familySelector("SBP"), OperationDirection.IN,
                new RuleSelector<>(AttributeSelectorType.PAYMENT_SYSTEM, "MIR"));

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .satisfies(error -> assertThat(((RuleManifestProblemException) error).diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MANIFEST_DICTIONARY_VALUE_DISABLED"));
    }

    @Test
    void rejectsTypeDirectionIncompatibleWithOperationType() {
        repository.addActiveRule("RULE_BAD_DIRECTION", typeSelector("SBP_C2B"), OperationDirection.OUT, noneSelector());

        assertThatThrownBy(() -> compiler.compile())
                .isInstanceOf(RuleManifestProblemException.class)
                .satisfies(error -> assertThat(((RuleManifestProblemException) error).diagnostics())
                        .extracting(diagnostic -> diagnostic.code())
                        .contains("MANIFEST_DIRECTION_CONFLICT"));
    }

    @Test
    void delegatesLatestManifestLookup() {
        repository.addActiveRule("RULE_A", typeSelector("SBP_C2B"), OperationDirection.IN, noneSelector());
        RuleManifest manifest = compiler.compile();

        assertThat(compiler.getLatest()).isEqualTo(manifest);
    }

    @Test
    void rejectsMissingManifestLookup() {
        assertThatThrownBy(() -> compiler.getManifest(UUID.randomUUID()))
                .isInstanceOf(RuleManifestProblemException.class)
                .hasMessageContaining("RULE_MANIFEST_NOT_FOUND");
    }

    private static RuleSelector<OperationSelectorType> anySelector() {
        return new RuleSelector<>(OperationSelectorType.ANY, null);
    }

    private static RuleSelector<OperationSelectorType> familySelector(String value) {
        return new RuleSelector<>(OperationSelectorType.FAMILY, value);
    }

    private static RuleSelector<OperationSelectorType> typeSelector(String value) {
        return new RuleSelector<>(OperationSelectorType.TYPE, value);
    }

    private static RuleSelector<AttributeSelectorType> noneSelector() {
        return new RuleSelector<>(AttributeSelectorType.NONE, null);
    }

    static class FakeManifestRepository implements RuleManifestRepository {
        final List<LimitRule> activeRules = new ArrayList<>();
        final List<RuleManifest> saved = new ArrayList<>();
        int nextVersion = 1;
        boolean paymentSystemsEnabled = true;

        LimitRule addActiveRule(
                String code,
                RuleSelector<OperationSelectorType> operationSelector,
                OperationDirection direction,
                RuleSelector<AttributeSelectorType> attributeSelector
        ) {
            LimitRule rule = new LimitRule(
                    UUID.randomUUID(),
                    code,
                    1,
                    code,
                    operationSelector,
                    direction,
                    attributeSelector,
                    LimitTargetType.PHONE,
                    RuleMetric.AMOUNT,
                    RulePeriod.DAY,
                    "RUB",
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
                    List.of(item("SBP", true), item("CARD", true)),
                    List.of(
                            operationType("SBP_C2B", "SBP", OperationDirection.IN, true),
                            operationType("SBP_B2C", "SBP", OperationDirection.OUT, true),
                            operationType("ECOM", "CARD", OperationDirection.IN, true)
                    ),
                    List.of(item("MIR", paymentSystemsEnabled)),
                    List.of(item("RU", true)),
                    List.of(item("TKB", true)),
                    List.of(item("220220", true)),
                    List.of(item("DEBIT", true)),
                    List.of(item("GOLD", true)),
                    Arrays.asList(OperationDirection.values()),
                    Arrays.asList(OperationSelectorType.values()),
                    Arrays.asList(AttributeSelectorType.values()),
                    Arrays.asList(LimitTargetType.values()),
                    Arrays.asList(RuleMetric.values()),
                    Arrays.asList(RulePeriod.values())
            );
        }

        @Override
        public int nextManifestVersion() {
            return nextVersion++;
        }

        @Override
        public RuleManifest saveManifest(RuleManifest manifest) {
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
            return new OperationType(UUID.randomUUID(), code, code, familyCode, direction, enabled, 10, Instant.EPOCH, Instant.EPOCH);
        }
    }
}
