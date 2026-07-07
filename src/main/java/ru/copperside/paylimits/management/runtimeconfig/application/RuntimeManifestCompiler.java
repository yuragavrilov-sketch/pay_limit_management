package ru.copperside.paylimits.management.runtimeconfig.application;

import ru.copperside.paylimits.management.common.invariant.LimitKindConflict;
import ru.copperside.paylimits.management.common.invariant.LimitKindConflictException;
import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker;
import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker.SnapshotGroupAssignment;
import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker.SnapshotMembership;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitrule.domain.LimitKind;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;
import ru.copperside.paylimits.management.runtimeconfig.application.port.out.RuntimeManifestRepository;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledRule;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestLifecycleStatus;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestPayload;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestProblemException;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestStatus;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RuntimeManifestCompiler {

    private final RuntimeManifestRepository repository;
    private final Clock clock;
    private final Duration minActivationLeadTime;
    private final RuntimeManifestCanonicalJson canonicalJson;

    public RuntimeManifestCompiler(
            RuntimeManifestRepository repository,
            Clock clock,
            Duration minActivationLeadTime
    ) {
        this(repository, clock, minActivationLeadTime, new RuntimeManifestCanonicalJson());
    }

    RuntimeManifestCompiler(
            RuntimeManifestRepository repository,
            Clock clock,
            Duration minActivationLeadTime,
            RuntimeManifestCanonicalJson canonicalJson
    ) {
        this.repository = repository;
        this.clock = clock;
        this.minActivationLeadTime = minActivationLeadTime;
        this.canonicalJson = canonicalJson;
    }

    public RuntimeManifest compile(Instant effectiveFrom) {
        Instant now = canonicalInstant(Instant.now(clock));
        validateEffectiveFrom(effectiveFrom, now);
        Instant canonicalEffectiveFrom = canonicalInstant(effectiveFrom);
        return repository.saveCompiledManifest(version -> buildManifest(version, now, canonicalEffectiveFrom));
    }

    public RuntimeManifest getManifest(UUID id) {
        if (id == null) {
            throw notFound();
        }
        return repository.findManifest(id).orElseThrow(this::notFound);
    }

    public RuntimeManifest getEffective(Instant at) {
        if (at == null) {
            throw new RuntimeManifestProblemException("VALIDATION_ERROR", "at must not be null");
        }
        return repository.findEffectiveManifest(at).orElseThrow(this::notFound);
    }

    public List<RuntimeManifestDescriptor> listScheduled(Instant after, int limit) {
        if (after == null) {
            throw new RuntimeManifestProblemException("VALIDATION_ERROR", "after must not be null");
        }
        if (limit < 1) {
            throw new RuntimeManifestProblemException("VALIDATION_ERROR", "limit must be positive");
        }
        return repository.listScheduledManifests(after, limit).stream()
                .map(descriptor -> new RuntimeManifestDescriptor(
                        descriptor.id(),
                        descriptor.version(),
                        descriptor.checksum(),
                        descriptor.createdAt(),
                        descriptor.effectiveFrom(),
                        RuntimeManifestLifecycleStatus.SCHEDULED
                ))
                .toList();
    }

    public List<RuntimeManifestDescriptor> listLifecycle(Instant at, int limit) {
        if (at == null) {
            throw new RuntimeManifestProblemException("VALIDATION_ERROR", "at must not be null");
        }
        if (limit < 1) {
            throw new RuntimeManifestProblemException("VALIDATION_ERROR", "limit must be positive");
        }
        Integer activeVersion = repository.findEffectiveManifest(at)
                .map(RuntimeManifest::version)
                .orElse(null);
        return repository.listManifests(limit).stream()
                .map(descriptor -> withLifecycleStatus(descriptor, at, activeVersion))
                .toList();
    }

    public RuntimeManifest rollback(UUID sourceManifestId, Instant effectiveFrom) {
        RuntimeManifest source = getManifest(sourceManifestId);
        Instant now = canonicalInstant(Instant.now(clock));
        validateEffectiveFrom(effectiveFrom, now);
        Instant canonicalEffectiveFrom = canonicalInstant(effectiveFrom);
        return repository.saveCompiledManifest(version -> buildRollbackManifest(source, version, now, canonicalEffectiveFrom));
    }

    public static RuntimeCompiledRule compileRule(LimitRule rule) {
        return new RuntimeCompiledRule(
                rule.id(),
                rule.code(),
                rule.version(),
                new RuntimeCompiledRule.Matcher(
                        rule.operationTypes().stream().sorted().toList(),
                        rule.direction(),
                        rule.attributeSelector(),
                        rule.limitTargetType()
                ),
                rule.measure(),
                rule.limitValue(),
                rule.errorMessageTemplate()
        );
    }

    private RuntimeManifest buildManifest(int version, Instant createdAt, Instant effectiveFrom) {
        List<LimitRule> activeRules = repository.listActiveRulesForCompilation().stream()
                .filter(LimitRule::active)
                .toList();
        List<RuntimeCompiledRule> rules = activeRules.stream()
                .sorted(Comparator.comparing(LimitRule::code)
                        .thenComparingInt(LimitRule::version)
                        .thenComparing(rule -> rule.id().toString()))
                .map(RuntimeManifestCompiler::compileRule)
                .toList();
        List<RuntimeCompiledAssignment> assignments = repository.listEnabledAssignmentsForCompilation().stream()
                .sorted(Comparator.comparing(RuntimeCompiledAssignment::ruleCode)
                        .thenComparing(assignment -> assignment.ownerType().name())
                        .thenComparing(RuntimeCompiledAssignment::ownerId)
                        .thenComparing(assignment -> assignment.assignmentId().toString()))
                .toList();
        List<RuntimeMerchantGroupMembership> memberships = repository.listMembershipsForCompilation().stream()
                .sorted(Comparator.comparing(RuntimeMerchantGroupMembership::merchantId)
                        .thenComparing(membership -> membership.groupTypeId().toString())
                        .thenComparing(RuntimeMerchantGroupMembership::validFrom)
                        .thenComparing(membership -> membership.membershipId().toString()))
                .toList();
        checkSnapshotInvariant(activeRules, assignments, memberships);
        RuntimeManifestPayload payload = new RuntimeManifestPayload(
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

    /**
     * Re-checks the limit-kind non-overlap invariant over the compiled snapshot before the manifest
     * is persisted (last line of defence, spec §3.4). Uses only the data already loaded for this
     * compilation — no additional queries — and, on any conflict, aborts compilation with a 422 so the
     * manifest is never created.
     */
    private void checkSnapshotInvariant(
            List<LimitRule> activeRules,
            List<RuntimeCompiledAssignment> assignments,
            List<RuntimeMerchantGroupMembership> memberships
    ) {
        Map<UUID, LimitKind> ruleKinds = new HashMap<>();
        for (LimitRule rule : activeRules) {
            ruleKinds.put(rule.id(), LimitKind.of(rule));
        }
        List<SnapshotGroupAssignment> groupAssignments = assignments.stream()
                .filter(assignment -> assignment.ownerType() == AssignmentOwnerType.MERCHANT_GROUP)
                .map(assignment -> new SnapshotGroupAssignment(
                        UUID.fromString(assignment.ownerId()), assignment.ruleId()))
                .toList();
        List<SnapshotMembership> snapshotMemberships = memberships.stream()
                .map(membership -> new SnapshotMembership(membership.merchantId(), membership.groupId()))
                .toList();
        List<LimitKindConflict> conflicts = LimitKindInvariantChecker.findSnapshotConflicts(
                snapshotMemberships, groupAssignments, ruleKinds);
        if (!conflicts.isEmpty()) {
            throw new LimitKindConflictException(conflicts, true);
        }
    }

    private RuntimeManifest buildRollbackManifest(
            RuntimeManifest source,
            int version,
            Instant createdAt,
            Instant effectiveFrom
    ) {
        RuntimeManifestPayload payload = new RuntimeManifestPayload(
                version,
                RuntimeManifestStatus.VALID,
                createdAt,
                effectiveFrom,
                source.ruleCount(),
                source.assignmentCount(),
                source.membershipCount(),
                source.rules(),
                source.assignments(),
                source.memberships(),
                source.diagnostics()
        );
        return new RuntimeManifest(
                UUID.randomUUID(),
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

    private RuntimeManifestDescriptor withLifecycleStatus(
            RuntimeManifestDescriptor descriptor,
            Instant at,
            Integer activeVersion
    ) {
        RuntimeManifestLifecycleStatus lifecycleStatus;
        if (descriptor.effectiveFrom().isAfter(at)) {
            lifecycleStatus = RuntimeManifestLifecycleStatus.SCHEDULED;
        } else if (activeVersion != null && descriptor.version() == activeVersion) {
            lifecycleStatus = RuntimeManifestLifecycleStatus.ACTIVE;
        } else {
            lifecycleStatus = RuntimeManifestLifecycleStatus.SUPERSEDED;
        }
        return new RuntimeManifestDescriptor(
                descriptor.id(),
                descriptor.version(),
                descriptor.checksum(),
                descriptor.createdAt(),
                descriptor.effectiveFrom(),
                lifecycleStatus
        );
    }

    private void validateEffectiveFrom(Instant effectiveFrom, Instant now) {
        if (effectiveFrom == null) {
            throw new RuntimeManifestProblemException("VALIDATION_ERROR", "effectiveFrom must not be null");
        }
        Duration leadTime = minActivationLeadTime == null ? Duration.ZERO : minActivationLeadTime;
        if (leadTime.isNegative()) {
            throw new RuntimeManifestProblemException("VALIDATION_ERROR", "min activation lead time must not be negative");
        }
        Instant minimumEffectiveFrom = now.plus(leadTime);
        if (effectiveFrom.isBefore(minimumEffectiveFrom)) {
            throw new RuntimeManifestProblemException(
                    "RUNTIME_MANIFEST_LEAD_TIME_VIOLATION",
                    "effectiveFrom must be at least " + leadTime + " after manifest creation time",
                    Map.of(
                            "effectiveFrom", effectiveFrom,
                            "minimumEffectiveFrom", minimumEffectiveFrom
                    )
            );
        }
    }

    private RuntimeManifestProblemException notFound() {
        return new RuntimeManifestProblemException("RUNTIME_MANIFEST_NOT_FOUND", "Runtime manifest not found");
    }

    private static Instant canonicalInstant(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

}
