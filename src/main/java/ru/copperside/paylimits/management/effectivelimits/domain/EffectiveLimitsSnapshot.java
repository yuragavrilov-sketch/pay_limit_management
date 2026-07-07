package ru.copperside.paylimits.management.effectivelimits.domain;

import java.time.Instant;
import java.util.List;

/**
 * Response of the effective-limits preview (spec §3.5): every limit kind resolved for a merchant
 * at instant {@code at} from the current management configuration, plus the version of the latest
 * compiled runtime manifest (a divergence between this snapshot and that manifest tells the
 * operator the configuration has changed since the last compilation).
 */
public record EffectiveLimitsSnapshot(
        String merchantId,
        Instant at,
        Integer manifestVersion,
        List<ResolvedLimit> limits
) {
    public EffectiveLimitsSnapshot {
        limits = limits == null ? List.of() : List.copyOf(limits);
    }
}
