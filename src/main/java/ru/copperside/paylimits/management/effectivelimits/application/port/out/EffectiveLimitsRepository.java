package ru.copperside.paylimits.management.effectivelimits.application.port.out;

import ru.copperside.paylimits.management.effectivelimits.domain.EffectiveLimitCandidate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port supplying the data the effective-limits preview needs (spec §3.5): candidate
 * assignments in effect for a merchant at an instant, and the latest compiled manifest version.
 */
public interface EffectiveLimitsRepository {

    /**
     * Enabled assignments of {@code ACTIVE} rules in effect for {@code merchantId} at {@code at}:
     * GLOBAL assignments; {@code MERCHANT_GROUP} assignments of every group the merchant is an
     * active member of at {@code at} (membership period contains {@code at}); and {@code MERCHANT}
     * assignments of the merchant itself. Each candidate's own period also contains {@code at}.
     */
    List<EffectiveLimitCandidate> findCandidateAssignments(String merchantId, Instant at);

    /**
     * The version of the latest compiled runtime manifest, or empty if none has been compiled yet.
     */
    Optional<Integer> findLatestManifestVersion();
}
