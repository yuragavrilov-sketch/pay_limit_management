package ru.copperside.paylimits.management.effectivelimits.adapter.in.web;

import ru.copperside.paylimits.management.effectivelimits.domain.EffectiveLimitsSnapshot;
import ru.copperside.paylimits.management.effectivelimits.domain.LimitOverride;
import ru.copperside.paylimits.management.effectivelimits.domain.ResolvedLimit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape for {@code GET /merchants/{merchantId}/effective-limits} (spec §3.5). Money is
 * carried as a string ({@code limitValue}), per the service-wide convention.
 */
public record EffectiveLimitsResponse(
        String merchantId,
        Instant at,
        Integer manifestVersion,
        List<AppliedLimitResponse> limits
) {
    public static EffectiveLimitsResponse from(EffectiveLimitsSnapshot snapshot) {
        return new EffectiveLimitsResponse(
                snapshot.merchantId(),
                snapshot.at(),
                snapshot.manifestVersion(),
                snapshot.limits().stream().map(AppliedLimitResponse::from).toList());
    }

    public record AppliedLimitResponse(
            String ruleCode,
            int ruleVersion,
            String limitType,
            String targetType,
            String direction,
            List<String> operationTypes,
            String appliedLevel,
            String ownerId,
            String mode,
            String limitValue,
            UUID assignmentId,
            List<OverrideResponse> overrides
    ) {
        public static AppliedLimitResponse from(ResolvedLimit limit) {
            return new AppliedLimitResponse(
                    limit.ruleCode(),
                    limit.ruleVersion(),
                    limit.limitType(),
                    limit.targetType() == null ? null : limit.targetType().name(),
                    limit.direction() == null ? null : limit.direction().name(),
                    limit.operationTypes().stream().sorted().toList(),
                    limit.appliedLevel().name(),
                    limit.ownerId(),
                    limit.mode().name(),
                    limit.limitValue() == null ? null : limit.limitValue().toPlainString(),
                    limit.assignmentId(),
                    limit.overrides().stream().map(OverrideResponse::from).toList());
        }
    }

    public record OverrideResponse(
            String level,
            String ownerId,
            String limitValue
    ) {
        public static OverrideResponse from(LimitOverride override) {
            return new OverrideResponse(
                    override.level().name(),
                    override.ownerId(),
                    override.limitValue() == null ? null : override.limitValue().toPlainString());
        }
    }
}
