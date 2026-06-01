package ru.copperside.paylimits.management.runtimeconfig.application;

import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;
import ru.copperside.paylimits.management.limitrule.domain.OperationSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
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

    public static RuntimeCompiledRule compileRule(LimitRule rule, List<OperationType> operationTypes) {
        return new RuntimeCompiledRule(
                rule.id(),
                rule.code(),
                rule.version(),
                new RuntimeCompiledRule.Matcher(
                        rule.operationSelector(),
                        rule.operationSelector().type() == OperationSelectorType.ANY,
                        operationTypeCodes(rule.operationSelector(), operationTypes),
                        rule.direction(),
                        rule.attributeSelector(),
                        rule.targetType()
                ),
                new RuntimeCompiledRule.Measure(
                        rule.metric(),
                        rule.period(),
                        rule.currency()
                )
        );
    }

    private RuntimeManifest buildManifest(int version, Instant createdAt, Instant effectiveFrom) {
        List<OperationType> operationTypes = repository.listOperationTypesForCompilation();
        List<RuntimeCompiledRule> rules = repository.listActiveRulesForCompilation().stream()
                .filter(LimitRule::active)
                .sorted(Comparator.comparing(LimitRule::code)
                        .thenComparingInt(LimitRule::version)
                        .thenComparing(rule -> rule.id().toString()))
                .map(rule -> compileRule(rule, operationTypes))
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

    private static List<String> operationTypeCodes(
            RuleSelector<OperationSelectorType> selector,
            List<OperationType> operationTypes
    ) {
        return switch (selector.type()) {
            case ANY -> List.of();
            case TYPE -> List.of(selector.value());
            case FAMILY -> operationTypes.stream()
                    .filter(OperationType::enabled)
                    .filter(type -> selector.value().equals(type.familyCode()))
                    .sorted(Comparator.comparingInt(OperationType::sortOrder)
                            .thenComparing(OperationType::code))
                    .map(OperationType::code)
                    .toList();
        };
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
