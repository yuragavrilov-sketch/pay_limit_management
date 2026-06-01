package ru.copperside.paylimits.management.runtimeconfig.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import ru.copperside.paylimits.management.runtimeconfig.application.port.out.RuntimeManifestRepository;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledRule;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestPayload;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestProblemException;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestStatus;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestLifecycleStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeManifestCompilerTest {

    private static final Instant NOW = Instant.parse("2026-05-29T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private FakeRepository repository;
    private RuntimeManifestCompiler compiler;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        compiler = new RuntimeManifestCompiler(repository, CLOCK, Duration.ofMinutes(5));
    }

    @Test
    void compilesActiveRulesAssignmentsAndMembershipsWithEffectiveFrom() {
        LimitRule rule = repository.addActiveRule("RULE_SBP_PHONE_DAY");
        RuntimeCompiledAssignment assignment = repository.addAssignment(rule.id(), rule.code(), AssignmentOwnerType.MERCHANT,
                "502118", LimitMode.LIMITED, "3000000.00");
        RuntimeMerchantGroupMembership membership = repository.addMembership("502118");
        Instant effectiveFrom = Instant.parse("2026-05-29T10:15:00Z");

        RuntimeManifest manifest = compiler.compile(effectiveFrom);

        assertThat(manifest.version()).isEqualTo(1);
        assertThat(manifest.status()).isEqualTo(RuntimeManifestStatus.VALID);
        assertThat(manifest.createdAt()).isEqualTo(NOW);
        assertThat(manifest.effectiveFrom()).isEqualTo(effectiveFrom);
        assertThat(manifest.ruleCount()).isEqualTo(1);
        assertThat(manifest.assignmentCount()).isEqualTo(1);
        assertThat(manifest.membershipCount()).isEqualTo(1);
        assertThat(manifest.rules()).extracting(RuntimeCompiledRule::ruleId).containsExactly(rule.id());
        assertThat(manifest.assignments()).containsExactly(assignment);
        assertThat(manifest.memberships()).containsExactly(membership);
        assertThat(repository.manifests).containsExactly(manifest);
    }

    @Test
    void expandsFamilyOperationSelectorForRuntimeRules() {
        repository.addOperationType("SBP_B2C", "SBP", OperationDirection.OUT, true, 20);
        repository.addOperationType("SBP_C2B", "SBP", OperationDirection.IN, true, 10);
        repository.addOperationType("SBP_DISABLED", "SBP", OperationDirection.IN, false, 5);
        repository.addOperationType("ECOM", "CARD", OperationDirection.IN, true, 30);
        repository.addActiveRule(
                "RULE_SBP_PHONE_DAY",
                new RuleSelector<>(OperationSelectorType.FAMILY, "SBP"));

        RuntimeManifest manifest = compiler.compile(Instant.parse("2026-05-29T10:15:00Z"));

        assertThat(manifest.rules()).hasSize(1);
        assertThat(manifest.rules().getFirst().matcher().operationMatchesAll()).isFalse();
        assertThat(manifest.rules().getFirst().matcher().operationTypeCodes())
                .containsExactly("SBP_C2B", "SBP_B2C");
    }

    @Test
    void rejectsEffectiveFromBeforeMinimumLeadTime() {
        repository.addActiveRule("RULE_SBP_PHONE_DAY");

        assertThatThrownBy(() -> compiler.compile(Instant.parse("2026-05-29T10:04:59Z")))
                .isInstanceOf(RuntimeManifestProblemException.class)
                .hasMessageContaining("RUNTIME_MANIFEST_LEAD_TIME_VIOLATION");

        assertThat(repository.manifests).isEmpty();
    }

    @Test
    void checksumChangesWhenOnlyEffectiveFromChanges() {
        LimitRule rule = repository.addActiveRule("RULE_SBP_PHONE_DAY");
        RuntimeCompiledRule compiledRule = RuntimeManifestCompiler.compileRule(rule, repository.operationTypes);
        RuntimeManifestCanonicalJson canonicalJson = new RuntimeManifestCanonicalJson();

        RuntimeManifestPayload first = payload(1, NOW, Instant.parse("2026-05-29T10:15:00Z"), List.of(compiledRule));
        RuntimeManifestPayload second = payload(1, NOW, Instant.parse("2026-05-29T10:30:00Z"), List.of(compiledRule));

        assertThat(canonicalJson.checksum(first)).isNotEqualTo(canonicalJson.checksum(second));
    }

    @Test
    void truncatesChecksumInstantsToPostgresPrecision() {
        Clock subMicroClock = Clock.fixed(Instant.parse("2026-05-29T10:00:00.123456789Z"), ZoneOffset.UTC);
        RuntimeManifestCompiler subMicroCompiler = new RuntimeManifestCompiler(
                repository,
                subMicroClock,
                Duration.ofMinutes(5));
        Instant effectiveFrom = Instant.parse("2026-05-29T10:15:00.987654321Z");

        RuntimeManifest manifest = subMicroCompiler.compile(effectiveFrom);

        assertThat(manifest.createdAt()).isEqualTo(subMicroClock.instant().truncatedTo(ChronoUnit.MICROS));
        assertThat(manifest.effectiveFrom()).isEqualTo(effectiveFrom.truncatedTo(ChronoUnit.MICROS));
        assertThat(manifest.checksum()).isEqualTo(new RuntimeManifestCanonicalJson().checksum(manifest.payload()));
    }

    @Test
    void sortsPayloadDeterministically() {
        LimitRule second = repository.addActiveRule("RULE_Z");
        LimitRule first = repository.addActiveRule("RULE_A");
        repository.addAssignment(second.id(), second.code(), AssignmentOwnerType.MERCHANT, "502119", LimitMode.BLOCKED, null);
        repository.addAssignment(first.id(), first.code(), AssignmentOwnerType.MERCHANT, "502118", LimitMode.UNLIMITED, null);
        repository.addMembership("502119");
        repository.addMembership("502118");

        RuntimeManifest manifest = compiler.compile(Instant.parse("2026-05-29T10:15:00Z"));

        assertThat(manifest.rules()).extracting(RuntimeCompiledRule::code).containsExactly("RULE_A", "RULE_Z");
        assertThat(manifest.assignments()).extracting(RuntimeCompiledAssignment::ruleCode).containsExactly("RULE_A", "RULE_Z");
        assertThat(manifest.memberships()).extracting(RuntimeMerchantGroupMembership::merchantId).containsExactly("502118", "502119");
    }

    @Test
    void listsRuntimeManifestLifecycleAtInstant() {
        LimitRule rule = repository.addActiveRule("RULE_SBP_PHONE_DAY");
        repository.addAssignment(rule.id(), rule.code(), AssignmentOwnerType.MERCHANT, "502118", LimitMode.LIMITED, "3000000.00");
        RuntimeManifest old = compiler.compile(Instant.parse("2026-05-29T10:15:00Z"));
        RuntimeManifest active = compiler.compile(Instant.parse("2026-05-29T10:30:00Z"));
        RuntimeManifest scheduled = compiler.compile(Instant.parse("2026-05-29T10:45:00Z"));

        var descriptors = compiler.listLifecycle(Instant.parse("2026-05-29T10:35:00Z"), 10);

        assertThat(descriptors).extracting(ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor::id)
                .containsExactly(scheduled.id(), active.id(), old.id());
        assertThat(descriptors).extracting(ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor::lifecycleStatus).containsExactly(
                RuntimeManifestLifecycleStatus.SCHEDULED,
                RuntimeManifestLifecycleStatus.ACTIVE,
                RuntimeManifestLifecycleStatus.SUPERSEDED);
    }

    @Test
    void rollbackCopiesOldPayloadIntoNewVersionWithNewEffectiveFrom() {
        LimitRule rule = repository.addActiveRule("RULE_SBP_PHONE_DAY");
        repository.addAssignment(rule.id(), rule.code(), AssignmentOwnerType.MERCHANT, "502118", LimitMode.LIMITED, "3000000.00");
        RuntimeManifest source = compiler.compile(Instant.parse("2026-05-29T10:15:00Z"));

        RuntimeManifest rollback = compiler.rollback(source.id(), Instant.parse("2026-05-29T10:30:00Z"));

        assertThat(rollback.version()).isEqualTo(2);
        assertThat(rollback.createdAt()).isEqualTo(NOW);
        assertThat(rollback.effectiveFrom()).isEqualTo(Instant.parse("2026-05-29T10:30:00Z"));
        assertThat(rollback.rules()).isEqualTo(source.rules());
        assertThat(rollback.assignments()).isEqualTo(source.assignments());
        assertThat(rollback.memberships()).isEqualTo(source.memberships());
        assertThat(rollback.checksum()).isEqualTo(new RuntimeManifestCanonicalJson().checksum(rollback.payload()));
        assertThat(rollback.checksum()).isNotEqualTo(source.checksum());
    }

    private RuntimeManifestPayload payload(int version, Instant createdAt, Instant effectiveFrom, List<RuntimeCompiledRule> rules) {
        return new RuntimeManifestPayload(
                version,
                RuntimeManifestStatus.VALID,
                createdAt,
                effectiveFrom,
                rules.size(),
                0,
                0,
                rules,
                List.of(),
                List.of(),
                List.of()
        );
    }

    static class FakeRepository implements RuntimeManifestRepository {

        final List<LimitRule> rules = new ArrayList<>();
        final List<OperationType> operationTypes = new ArrayList<>();
        final List<RuntimeCompiledAssignment> assignments = new ArrayList<>();
        final List<RuntimeMerchantGroupMembership> memberships = new ArrayList<>();
        final List<RuntimeManifest> manifests = new ArrayList<>();

        LimitRule addActiveRule(String code) {
            return addActiveRule(code, new RuleSelector<>(OperationSelectorType.TYPE, "SBP_C2B"));
        }

        LimitRule addActiveRule(String code, RuleSelector<OperationSelectorType> operationSelector) {
            LimitRule rule = new LimitRule(
                    UUID.randomUUID(),
                    code,
                    1,
                    code,
                    operationSelector,
                    OperationDirection.IN,
                    new RuleSelector<>(AttributeSelectorType.NONE, null),
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
            rules.add(rule);
            return rule;
        }

        OperationType addOperationType(
                String code,
                String familyCode,
                OperationDirection direction,
                boolean enabled,
                int sortOrder
        ) {
            OperationType operationType = new OperationType(
                    UUID.randomUUID(),
                    code,
                    code,
                    familyCode,
                    direction,
                    enabled,
                    sortOrder,
                    Instant.EPOCH,
                    Instant.EPOCH
            );
            operationTypes.add(operationType);
            return operationType;
        }

        RuntimeCompiledAssignment addAssignment(
                UUID ruleId,
                String ruleCode,
                AssignmentOwnerType ownerType,
                String ownerId,
                LimitMode mode,
                String limitValue
        ) {
            RuntimeCompiledAssignment assignment = new RuntimeCompiledAssignment(
                    UUID.randomUUID(),
                    ruleId,
                    ruleCode,
                    ownerType,
                    ownerId,
                    mode,
                    limitValue,
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

        @Override
        public List<LimitRule> listActiveRulesForCompilation() {
            return rules.stream()
                    .sorted(Comparator.comparing(LimitRule::code))
                    .toList();
        }

        @Override
        public List<OperationType> listOperationTypesForCompilation() {
            return List.copyOf(operationTypes);
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
        public List<ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor> listScheduledManifests(
                Instant after,
                int limit
        ) {
            return List.of();
        }

        @Override
        public List<ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor> listManifests(int limit) {
            return manifests.stream()
                    .sorted(Comparator.comparingInt(RuntimeManifest::version).reversed())
                    .limit(limit)
                    .map(manifest -> new ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor(
                            manifest.id(),
                            manifest.version(),
                            manifest.checksum(),
                            manifest.createdAt(),
                            manifest.effectiveFrom(),
                            null
                    ))
                    .toList();
        }
    }
}
