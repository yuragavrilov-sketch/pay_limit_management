package ru.copperside.paylimits.management.common.invariant;

import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;
import ru.copperside.paylimits.management.limitrule.domain.LimitKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Fixtures for datasource-excluded slice tests (fake-repository controller tests): a pass-through
 * {@link TransactionRunner} and a no-op {@link LimitKindInvariantChecker}. These tests never seed
 * conflicting limit-kind data, so the invariant check must be a harmless no-op there; the real
 * enforcement is exercised by the Testcontainers integration tests.
 */
public final class InvariantTestSupport {

    private InvariantTestSupport() {
    }

    public static LimitKindInvariantChecker noOpChecker() {
        return new LimitKindInvariantChecker(new NoOpRepository());
    }

    /** Runs the supplied work directly, mirroring an already-open transaction. */
    public static final class PassThroughTransactionRunner implements TransactionRunner {
        @Override
        public <T> T run(Supplier<T> work) {
            return work.get();
        }
    }

    private static final class NoOpRepository implements LimitKindInvariantRepository {
        @Override
        public void lockMerchant(String merchantId) {
        }

        @Override
        public void lockRule(java.util.UUID ruleId) {
        }

        @Override
        public List<LimitKind> kindsDeliveredByGroup(java.util.UUID groupId) {
            return List.of();
        }

        @Override
        public List<String> membersOfGroup(java.util.UUID groupId, Instant at) {
            return List.of();
        }

        @Override
        public List<MerchantGroupKind> kindsReceivedByMerchantExcludingGroup(
                String merchantId, java.util.UUID excludedGroupId, Instant at) {
            return List.of();
        }

        @Override
        public List<MerchantGroupKind> kindsReceivedByMerchantExcludingGroups(
                String merchantId, java.util.Collection<java.util.UUID> excludedGroupIds, Instant at) {
            return List.of();
        }

        @Override
        public List<java.util.UUID> groupsWithEnabledAssignmentForRule(java.util.UUID ruleId) {
            return List.of();
        }

        @Override
        public Optional<LimitKind> kindOfRule(java.util.UUID ruleId) {
            return Optional.empty();
        }
    }
}
