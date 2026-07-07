package ru.copperside.paylimits.management.effectivelimits.application;

import org.junit.jupiter.api.Test;
import ru.copperside.paylimits.management.effectivelimits.application.port.out.EffectiveLimitsRepository;
import ru.copperside.paylimits.management.effectivelimits.domain.EffectiveLimitCandidate;
import ru.copperside.paylimits.management.effectivelimits.domain.EffectiveLimitsSnapshot;
import ru.copperside.paylimits.management.effectivelimits.domain.LimitOverride;
import ru.copperside.paylimits.management.effectivelimits.domain.ResolvedLimit;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MGT-U-08 (spec §8): level-priority resolution (MERCHANT > MERCHANT_GROUP > GLOBAL) and the
 * {@code overrides} block of the effective-limits preview (spec §3.5), against a fake repository —
 * application layer only, no Spring context.
 */
class EffectiveLimitsServiceTest {

    private static final Instant AT = Instant.parse("2026-07-06T12:00:00Z");
    private static final String MERCHANT_ID = "M42";

    @Test
    void resolvesEachKindToItsMostSpecificLevelAndListsOverriddenCandidates() {
        // Kind A: COUNT_DAY / CARD / OUT / OCT — present at all three levels, MERCHANT must win.
        UUID merchantAssignmentId = UUID.randomUUID();
        FakeRepository repository = new FakeRepository();
        repository.candidates.add(candidate(
                AssignmentOwnerType.GLOBAL, null, LimitMode.LIMITED, "PAYOUT-CARD-COUNT-DAY", 2,
                RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD, OperationDirection.OUT,
                Set.of("OCT"), new BigDecimal("100"), UUID.randomUUID()));
        repository.candidates.add(candidate(
                AssignmentOwnerType.MERCHANT_GROUP, "group-metallists", LimitMode.LIMITED, "PAYOUT-CARD-COUNT-DAY", 2,
                RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD, OperationDirection.OUT,
                Set.of("OCT"), new BigDecimal("3"), UUID.randomUUID()));
        repository.candidates.add(candidate(
                AssignmentOwnerType.MERCHANT, MERCHANT_ID, LimitMode.LIMITED, "PAYOUT-CARD-COUNT-DAY", 2,
                RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.CARD, OperationDirection.OUT,
                Set.of("OCT"), new BigDecimal("10"), merchantAssignmentId));
        repository.manifestVersion = 42;

        EffectiveLimitsSnapshot snapshot = new EffectiveLimitsService(repository).resolve(MERCHANT_ID, AT);

        assertThat(snapshot.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(snapshot.at()).isEqualTo(AT);
        assertThat(snapshot.manifestVersion()).isEqualTo(42);
        assertThat(snapshot.limits()).hasSize(1);

        ResolvedLimit limit = snapshot.limits().get(0);
        assertThat(limit.ruleCode()).isEqualTo("PAYOUT-CARD-COUNT-DAY");
        assertThat(limit.ruleVersion()).isEqualTo(2);
        assertThat(limit.limitType()).isEqualTo("COUNT_DAY");
        assertThat(limit.targetType()).isEqualTo(LimitTargetType.CARD);
        assertThat(limit.direction()).isEqualTo(OperationDirection.OUT);
        assertThat(limit.operationTypes()).containsExactly("OCT");
        assertThat(limit.appliedLevel()).isEqualTo(AssignmentOwnerType.MERCHANT);
        assertThat(limit.ownerId()).isEqualTo(MERCHANT_ID);
        assertThat(limit.mode()).isEqualTo(LimitMode.LIMITED);
        assertThat(limit.limitValue()).isEqualByComparingTo("10");
        assertThat(limit.assignmentId()).isEqualTo(merchantAssignmentId);

        assertThat(limit.overrides()).extracting(
                        LimitOverride::level, LimitOverride::ownerId, o -> o.limitValue().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(AssignmentOwnerType.MERCHANT_GROUP, "group-metallists", "3"),
                        org.assertj.core.groups.Tuple.tuple(AssignmentOwnerType.GLOBAL, null, "100"));
    }

    @Test
    void appliesGlobalLevelWhenNoMoreSpecificCandidateExists() {
        FakeRepository repository = new FakeRepository();
        repository.candidates.add(candidate(
                AssignmentOwnerType.GLOBAL, null, LimitMode.LIMITED, "PAYOUT-PHONE-COUNT-DAY", 1,
                RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE, OperationDirection.IN,
                Set.of("SBP_C2B"), new BigDecimal("5"), UUID.randomUUID()));

        EffectiveLimitsSnapshot snapshot = new EffectiveLimitsService(repository).resolve(MERCHANT_ID, AT);

        assertThat(snapshot.manifestVersion()).isNull();
        assertThat(snapshot.limits()).hasSize(1);
        ResolvedLimit limit = snapshot.limits().get(0);
        assertThat(limit.appliedLevel()).isEqualTo(AssignmentOwnerType.GLOBAL);
        assertThat(limit.ownerId()).isNull();
        assertThat(limit.limitValue()).isEqualByComparingTo("5");
        assertThat(limit.overrides()).isEmpty();
    }

    @Test
    void unlimitedAtMostSpecificLevelMeansTheKindIsNotEnforcedButStillReported() {
        FakeRepository repository = new FakeRepository();
        UUID merchantAssignmentId = UUID.randomUUID();
        repository.candidates.add(candidate(
                AssignmentOwnerType.MERCHANT_GROUP, "group-metallists", LimitMode.LIMITED, "PAYOUT-ACCOUNT-AMOUNT-DAY", 1,
                RuleMetric.AMOUNT, RulePeriod.DAY, LimitTargetType.ACCOUNT, OperationDirection.OUT,
                Set.of("PAYOUT"), new BigDecimal("30000.00"), UUID.randomUUID()));
        repository.candidates.add(candidate(
                AssignmentOwnerType.MERCHANT, MERCHANT_ID, LimitMode.UNLIMITED, "PAYOUT-ACCOUNT-AMOUNT-DAY", 1,
                RuleMetric.AMOUNT, RulePeriod.DAY, LimitTargetType.ACCOUNT, OperationDirection.OUT,
                Set.of("PAYOUT"), null, merchantAssignmentId));

        EffectiveLimitsSnapshot snapshot = new EffectiveLimitsService(repository).resolve(MERCHANT_ID, AT);

        assertThat(snapshot.limits()).hasSize(1);
        ResolvedLimit limit = snapshot.limits().get(0);
        assertThat(limit.appliedLevel()).isEqualTo(AssignmentOwnerType.MERCHANT);
        assertThat(limit.mode()).isEqualTo(LimitMode.UNLIMITED);
        assertThat(limit.limitValue()).isNull();
        assertThat(limit.assignmentId()).isEqualTo(merchantAssignmentId);
        assertThat(limit.overrides()).hasSize(1);
        assertThat(limit.overrides().get(0).level()).isEqualTo(AssignmentOwnerType.MERCHANT_GROUP);
        assertThat(limit.overrides().get(0).limitValue()).isEqualByComparingTo("30000.00");
    }

    @Test
    void intervalKindHasNoNumericLimitValueEvenWhenLimited() {
        FakeRepository repository = new FakeRepository();
        repository.candidates.add(candidate(
                AssignmentOwnerType.GLOBAL, null, LimitMode.LIMITED, "REPEAT-CARD-INTERVAL", 1,
                RuleMetric.INTERVAL, null, LimitTargetType.CARD, OperationDirection.OUT,
                Set.of("OCT"), null, UUID.randomUUID()));

        EffectiveLimitsSnapshot snapshot = new EffectiveLimitsService(repository).resolve(MERCHANT_ID, AT);

        ResolvedLimit limit = snapshot.limits().get(0);
        assertThat(limit.limitType()).isEqualTo("INTERVAL");
        assertThat(limit.limitValue()).isNull();
    }

    private static EffectiveLimitCandidate candidate(
            AssignmentOwnerType level,
            String ownerId,
            LimitMode mode,
            String ruleCode,
            int ruleVersion,
            RuleMetric metric,
            RulePeriod period,
            LimitTargetType targetType,
            OperationDirection direction,
            Set<String> operationTypes,
            BigDecimal limitValue,
            UUID assignmentId
    ) {
        return new EffectiveLimitCandidate(
                assignmentId, level, ownerId, mode, UUID.randomUUID(), ruleCode, ruleVersion,
                direction, metric, period, targetType, operationTypes, limitValue);
    }

    static class FakeRepository implements EffectiveLimitsRepository {
        final List<EffectiveLimitCandidate> candidates = new java.util.ArrayList<>();
        Integer manifestVersion;

        @Override
        public List<EffectiveLimitCandidate> findCandidateAssignments(String merchantId, Instant at) {
            return List.copyOf(candidates);
        }

        @Override
        public Optional<Integer> findLatestManifestVersion() {
            return Optional.ofNullable(manifestVersion);
        }
    }
}
