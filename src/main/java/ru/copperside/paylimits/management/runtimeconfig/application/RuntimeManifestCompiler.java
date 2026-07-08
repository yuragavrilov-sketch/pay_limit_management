package ru.copperside.paylimits.management.runtimeconfig.application;

import ru.copperside.paylimits.management.audit.application.AuditRecorder;
import ru.copperside.paylimits.management.common.invariant.LimitKindConflict;
import ru.copperside.paylimits.management.common.invariant.LimitKindConflictException;
import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;
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
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeOperationType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RuntimeManifestCompiler {

    public static final int SCHEMA_VERSION = 2;

    private static final String ENTITY_RUNTIME_MANIFEST = "RUNTIME_MANIFEST";

    private final RuntimeManifestRepository repository;
    private final Clock clock;
    private final Duration minActivationLeadTime;
    private final String businessTimezone;
    private final TransactionRunner transactionRunner;
    private final AuditRecorder auditRecorder;
    private final RuntimeManifestCanonicalJson canonicalJson;

    public RuntimeManifestCompiler(
            RuntimeManifestRepository repository,
            Clock clock,
            Duration minActivationLeadTime,
            String businessTimezone,
            TransactionRunner transactionRunner,
            AuditRecorder auditRecorder
    ) {
        this(repository, clock, minActivationLeadTime, businessTimezone, transactionRunner, auditRecorder,
                new RuntimeManifestCanonicalJson());
    }

    RuntimeManifestCompiler(
            RuntimeManifestRepository repository,
            Clock clock,
            Duration minActivationLeadTime,
            String businessTimezone,
            TransactionRunner transactionRunner,
            AuditRecorder auditRecorder,
            RuntimeManifestCanonicalJson canonicalJson
    ) {
        this.repository = repository;
        this.clock = clock;
        this.minActivationLeadTime = minActivationLeadTime;
        this.businessTimezone = validateBusinessTimezone(businessTimezone);
        this.transactionRunner = transactionRunner;
        this.auditRecorder = auditRecorder;
        this.canonicalJson = canonicalJson;
    }

    private static String validateBusinessTimezone(String businessTimezone) {
        if (businessTimezone == null) {
            throw new IllegalArgumentException("businessTimezone must not be null");
        }
        ZoneId.of(businessTimezone);
        return businessTimezone;
    }

    public RuntimeManifest compile(Instant effectiveFrom) {
        Instant now = canonicalInstant(Instant.now(clock));
        validateEffectiveFrom(effectiveFrom, now);
        Instant canonicalEffectiveFrom = canonicalInstant(effectiveFrom);
        // The manifest persist (repository.saveCompiledManifest) is itself @Transactional(REPEATABLE_READ);
        // wrapping it in transactionRunner.runRepeatableRead makes the persist and the audit append share
        // ONE REPEATABLE_READ transaction (the inner @Transactional joins via default REQUIRED propagation,
        // keeping its LOCK TABLE / version logic intact and inheriting the outer isolation level per spec
        // §4.2 step 2 — a participating @Transactional method cannot upgrade the already-open transaction's
        // isolation), so the manifest row and its audit event commit or roll back together under a
        // consistent compilation snapshot.
        return transactionRunner.runRepeatableRead(() -> {
            RuntimeManifest manifest = repository.saveCompiledManifest(
                    version -> buildManifest(version, now, canonicalEffectiveFrom));
            recordManifestAudit("COMPILE", manifest);
            return manifest;
        });
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
        return transactionRunner.runRepeatableRead(() -> {
            RuntimeManifest manifest = repository.saveCompiledManifest(
                    version -> buildRollbackManifest(source, version, now, canonicalEffectiveFrom));
            recordManifestAudit("ROLLBACK", manifest);
            return manifest;
        });
    }

    /**
     * Appends the COMPILE/ROLLBACK audit event for a freshly persisted manifest. {@code before} is
     * always {@code null} (a manifest is immutable and only ever created); {@code after} carries a
     * compact {@link RuntimeManifestDescriptor} (id/version/checksum/timestamps) rather than the full
     * payload, keeping the audit row small while still identifying the exact manifest produced.
     */
    private void recordManifestAudit(String action, RuntimeManifest manifest) {
        RuntimeManifestDescriptor descriptor = new RuntimeManifestDescriptor(
                manifest.id(),
                manifest.version(),
                manifest.checksum(),
                manifest.createdAt(),
                manifest.effectiveFrom(),
                null);
        auditRecorder.record(ENTITY_RUNTIME_MANIFEST, manifest.id().toString(), action, null, descriptor);
    }

    public static RuntimeCompiledRule compileRule(LimitRule rule) {
        // An ACTIVE rule with no operation types would compile to a matcher whose operationTypes list
        // is empty. The engine treats a manifest matcher as "matches when every listed dimension
        // matches", so an empty operationTypes list is an all-operations wildcard — never the intent of
        // an admin who simply left the set empty. The sibling RuleManifestCompiler.validateStructure
        // already rejects this via a diagnostic; guard the runtime compiler the same way so such a rule
        // aborts compilation (422) instead of shipping an empty-matcher rule to the engine.
        if (rule.operationTypes().isEmpty()) {
            throw new RuntimeManifestProblemException(
                    "RUNTIME_MANIFEST_INVALID_RULE",
                    "Active rule must reference at least one operation type: " + rule.code());
        }
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
                        .thenComparing(RuntimeCompiledAssignment::ownerId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(assignment -> assignment.assignmentId().toString()))
                .toList();
        List<RuntimeMerchantGroupMembership> memberships = repository.listMembershipsForCompilation().stream()
                .sorted(Comparator.comparing(RuntimeMerchantGroupMembership::merchantId)
                        .thenComparing(membership -> membership.groupTypeId().toString())
                        .thenComparing(RuntimeMerchantGroupMembership::validFrom)
                        .thenComparing(membership -> membership.membershipId().toString()))
                .toList();
        List<RuntimeOperationType> operationTypes = repository.listOperationTypesForManifest().stream()
                .sorted(Comparator.comparing(RuntimeOperationType::code))
                .toList();
        checkSnapshotInvariant(activeRules, assignments, memberships, createdAt);
        RuntimeManifestPayload payload = new RuntimeManifestPayload(
                SCHEMA_VERSION,
                businessTimezone,
                operationTypes,
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

    /**
     * Re-checks the limit-kind non-overlap invariant over the compiled snapshot before the manifest
     * is persisted (last line of defence, spec §3.4). Uses only the data already loaded for this
     * compilation — no additional queries — and, on any conflict, aborts compilation with a 422 so the
     * manifest is never created.
     *
     * <p>Only memberships that are active-or-future at {@code compileInstant}
     * ({@code validTo == null || validTo.isAfter(compileInstant)}) participate in the scan, mirroring
     * the interactive membership check (which filters {@code valid_to > now}). CLOSED/historical
     * memberships are excluded here so a merchant's past membership in one group and current
     * membership in another delivering the same kind is not treated as a simultaneous overlap.
     *
     * <p>The same temporal rule applies to group ASSIGNMENTS: only assignments whose validity window
     * contains {@code compileInstant} ({@code validFrom <= compileInstant &&
     * (validTo == null || validTo.isAfter(compileInstant))}) deliver their kind, mirroring the
     * interactive checks' assignment-window filter. An expired-but-enabled assignment is therefore
     * ignored by the scan. This filtering affects the invariant scan ONLY — the manifest payload (and
     * therefore its checksum) still carries every membership and assignment row returned for
     * compilation, unchanged (engine performs its own temporal selection over the full periods).
     */
    private void checkSnapshotInvariant(
            List<LimitRule> activeRules,
            List<RuntimeCompiledAssignment> assignments,
            List<RuntimeMerchantGroupMembership> memberships,
            Instant compileInstant
    ) {
        Map<UUID, LimitKind> ruleKinds = new HashMap<>();
        for (LimitRule rule : activeRules) {
            ruleKinds.put(rule.id(), LimitKind.of(rule));
        }
        List<SnapshotGroupAssignment> groupAssignments = assignments.stream()
                .filter(assignment -> assignment.ownerType() == AssignmentOwnerType.MERCHANT_GROUP)
                .filter(assignment -> isInEffect(assignment, compileInstant))
                .map(assignment -> new SnapshotGroupAssignment(
                        UUID.fromString(assignment.ownerId()), assignment.ruleId()))
                .toList();
        List<SnapshotMembership> snapshotMemberships = memberships.stream()
                .filter(membership -> membership.validTo() == null || membership.validTo().isAfter(compileInstant))
                .map(membership -> new SnapshotMembership(membership.merchantId(), membership.groupId()))
                .toList();
        List<LimitKindConflict> conflicts = LimitKindInvariantChecker.findSnapshotConflicts(
                snapshotMemberships, groupAssignments, ruleKinds);
        if (!conflicts.isEmpty()) {
            throw new LimitKindConflictException(conflicts, true);
        }
    }

    /**
     * Whether an assignment's validity window contains {@code at}, i.e. it is actually in effect at
     * that instant. {@code validFrom} is non-null for a compiled assignment; a null {@code validTo}
     * means open-ended.
     */
    private static boolean isInEffect(RuntimeCompiledAssignment assignment, Instant at) {
        return !assignment.validFrom().isAfter(at)
                && (assignment.validTo() == null || assignment.validTo().isAfter(at));
    }

    private RuntimeManifest buildRollbackManifest(
            RuntimeManifest source,
            int version,
            Instant createdAt,
            Instant effectiveFrom
    ) {
        // A rollback re-emits the SOURCE manifest's content unchanged under a new version/effectiveFrom
        // (spec: "rollback = new version with the old content, history stays linear"), so schemaVersion,
        // businessTimezone and operationTypes are carried over from the source rather than re-derived
        // from current config — a faithful rollback of content, not a re-compilation under today's schema.
        RuntimeManifestPayload payload = new RuntimeManifestPayload(
                source.schemaVersion(),
                source.businessTimezone(),
                source.operationTypes(),
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
