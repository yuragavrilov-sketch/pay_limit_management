package ru.copperside.paylimits.management.effectivelimits.application;

import ru.copperside.paylimits.management.common.invariant.LimitKindView;
import ru.copperside.paylimits.management.effectivelimits.application.port.out.EffectiveLimitsRepository;
import ru.copperside.paylimits.management.effectivelimits.domain.EffectiveLimitCandidate;
import ru.copperside.paylimits.management.effectivelimits.domain.EffectiveLimitsSnapshot;
import ru.copperside.paylimits.management.effectivelimits.domain.LimitOverride;
import ru.copperside.paylimits.management.effectivelimits.domain.ResolvedLimit;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitrule.domain.LimitKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a merchant's effective limits at a given instant from the CURRENT management
 * configuration (spec §3.5) — a preview, not the engine's live counters.
 *
 * <p>For every limit kind (spec §2: metric/period/limitTargetType/direction/operationTypes) the
 * merchant is subject to at {@code at}, the candidate from the most specific owner level wins,
 * independently per kind: {@code MERCHANT > MERCHANT_GROUP > GLOBAL}. Levels never sum. The
 * overridden, less-specific candidates are surfaced in {@code overrides} for operator transparency.
 *
 * <p>Application layer: no Spring dependency, all I/O through {@link EffectiveLimitsRepository}.
 */
public class EffectiveLimitsService {

    private final EffectiveLimitsRepository repository;

    public EffectiveLimitsService(EffectiveLimitsRepository repository) {
        this.repository = repository;
    }

    public EffectiveLimitsSnapshot resolve(String merchantId, Instant at) {
        List<EffectiveLimitCandidate> candidates = repository.findCandidateAssignments(merchantId, at);

        Map<LimitKind, List<EffectiveLimitCandidate>> candidatesByKind = new LinkedHashMap<>();
        for (EffectiveLimitCandidate candidate : candidates) {
            candidatesByKind.computeIfAbsent(candidate.kind(), key -> new java.util.ArrayList<>()).add(candidate);
        }

        List<ResolvedLimit> limits = candidatesByKind.values().stream()
                .map(this::resolveKind)
                .sorted(Comparator.comparing(ResolvedLimit::ruleCode).thenComparing(ResolvedLimit::limitType))
                .toList();

        Integer manifestVersion = repository.findLatestManifestVersion().orElse(null);
        return new EffectiveLimitsSnapshot(merchantId, at, manifestVersion, limits);
    }

    private ResolvedLimit resolveKind(List<EffectiveLimitCandidate> candidatesForKind) {
        Comparator<EffectiveLimitCandidate> bySpecificity = Comparator
                .<EffectiveLimitCandidate>comparingInt(candidate -> levelPriority(candidate.ownerLevel()))
                // Deterministic tie-break when two candidates land at the same level for the same kind.
                // NOTE: this is NOT invariant-guarded — the non-overlap invariant only checks kinds
                // delivered by DIFFERENT groups to a shared merchant; it never rejects two MERCHANT-level
                // (or two GLOBAL-level) assignments of different rules that resolve to the same LimitKind.
                // In that case one is deterministically chosen as applied and the other is listed under
                // overrides at the same level. Surfacing same-level duplicates distinctly is a separate,
                // system-wide decision (see backlog), not handled here.
                .thenComparing(EffectiveLimitCandidate::ruleCode)
                .thenComparing(candidate -> candidate.ownerId() == null ? "" : candidate.ownerId());

        EffectiveLimitCandidate applied = candidatesForKind.stream().min(bySpecificity).orElseThrow();

        List<LimitOverride> overrides = candidatesForKind.stream()
                .filter(candidate -> candidate != applied)
                .sorted(bySpecificity)
                .map(candidate -> new LimitOverride(
                        candidate.ownerLevel(), candidate.ownerId(), numericLimitValue(candidate)))
                .toList();

        String limitType = LimitKindView.of(applied.kind()).checkType();

        return new ResolvedLimit(
                applied.ruleCode(),
                applied.ruleVersion(),
                limitType,
                applied.limitTargetType(),
                applied.direction(),
                applied.operationTypes(),
                applied.ownerLevel(),
                applied.ownerId(),
                applied.mode(),
                numericLimitValue(applied),
                applied.assignmentId(),
                overrides);
    }

    /**
     * The rule's numeric limit only when the candidate's mode is {@code LIMITED}; {@code null} for
     * {@code UNLIMITED}/{@code BLOCKED} and (by DB invariant) for {@code INTERVAL}-metric rules,
     * whose {@code limit_value} is always {@code null}.
     */
    private BigDecimal numericLimitValue(EffectiveLimitCandidate candidate) {
        return candidate.mode() == LimitMode.LIMITED ? candidate.limitValue() : null;
    }

    private int levelPriority(AssignmentOwnerType level) {
        return switch (level) {
            case MERCHANT -> 0;
            case MERCHANT_GROUP -> 1;
            case GLOBAL -> 2;
        };
    }
}
