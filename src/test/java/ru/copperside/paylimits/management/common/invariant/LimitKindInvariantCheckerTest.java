package ru.copperside.paylimits.management.common.invariant;

import org.junit.jupiter.api.Test;
import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository;
import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository.MemberOtherGroupKind;
import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository.MerchantGroupKind;
import ru.copperside.paylimits.management.limitrule.domain.LimitKind;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;

import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker.SnapshotGroupAssignment;
import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker.SnapshotMembership;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LimitKindInvariantCheckerTest {

    private static final Instant AT = Instant.parse("2026-07-07T09:00:00Z");

    private static final LimitKind COUNT_DAY_PHONE_IN =
            new LimitKind(RuleMetric.COUNT, RulePeriod.DAY, LimitTargetType.PHONE, OperationDirection.IN, Set.of("SBP_C2B"));
    private static final LimitKind DISJOINT_AMOUNT_MONTH =
            new LimitKind(RuleMetric.AMOUNT, RulePeriod.MONTH, LimitTargetType.ACCOUNT, OperationDirection.OUT, Set.of("SBP_B2C"));

    private final LimitKindInvariantRepository repository = mock(LimitKindInvariantRepository.class);
    private final LimitKindInvariantChecker checker = new LimitKindInvariantChecker(repository);

    // ---- membership (checkpoint a) ----

    @Test
    void membershipConflictThrowsWithMerchantExistingAndRequestedIds() {
        String merchantId = "502118";
        UUID requestedGroup = UUID.randomUUID();
        UUID otherGroup = UUID.randomUUID();
        when(repository.kindsDeliveredByGroup(requestedGroup, AT)).thenReturn(List.of(COUNT_DAY_PHONE_IN));
        when(repository.kindsReceivedByMerchantExcludingGroup(merchantId, requestedGroup, AT))
                .thenReturn(List.of(new MerchantGroupKind(otherGroup, COUNT_DAY_PHONE_IN)));

        LimitKindConflictException ex = catchThrowableOfType(
                () -> checker.checkMembership(merchantId, requestedGroup, AT), LimitKindConflictException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.compilation()).isFalse();
        assertThat(ex.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.merchantId()).isEqualTo(merchantId);
            assertThat(conflict.existingGroupId()).isEqualTo(otherGroup);
            assertThat(conflict.requestedGroupId()).isEqualTo(requestedGroup);
            assertThat(conflict.limitKind().checkType()).isEqualTo("COUNT_DAY");
        });
        verify(repository).lockMerchant(merchantId);
    }

    @Test
    void membershipWithDisjointKindsDoesNotThrow() {
        String merchantId = "502118";
        UUID requestedGroup = UUID.randomUUID();
        UUID otherGroup = UUID.randomUUID();
        when(repository.kindsDeliveredByGroup(requestedGroup, AT)).thenReturn(List.of(COUNT_DAY_PHONE_IN));
        when(repository.kindsReceivedByMerchantExcludingGroup(merchantId, requestedGroup, AT))
                .thenReturn(List.of(new MerchantGroupKind(otherGroup, DISJOINT_AMOUNT_MONTH)));

        assertThatCode(() -> checker.checkMembership(merchantId, requestedGroup, AT)).doesNotThrowAnyException();
        verify(repository).lockMerchant(merchantId);
    }

    @Test
    void membershipShortCircuitsWhenGroupDeliversNoKinds() {
        String merchantId = "502118";
        UUID requestedGroup = UUID.randomUUID();
        when(repository.kindsDeliveredByGroup(requestedGroup, AT)).thenReturn(List.of());

        assertThatCode(() -> checker.checkMembership(merchantId, requestedGroup, AT)).doesNotThrowAnyException();
        verify(repository).lockMerchant(merchantId);
    }

    // ---- group assignment (checkpoint b) ----

    @Test
    void groupAssignmentConflictThrowsForEachAffectedMember() {
        UUID ruleId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID otherGroup = UUID.randomUUID();
        when(repository.kindOfRule(ruleId)).thenReturn(Optional.of(COUNT_DAY_PHONE_IN));
        when(repository.kindsReceivedByMembersOfGroup(groupId, AT))
                .thenReturn(List.of(new MemberOtherGroupKind("502118", otherGroup, COUNT_DAY_PHONE_IN)));

        LimitKindConflictException ex = catchThrowableOfType(
                () -> checker.checkGroupAssignment(ruleId, groupId, AT), LimitKindConflictException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.merchantId()).isEqualTo("502118");
            assertThat(conflict.existingGroupId()).isEqualTo(otherGroup);
            assertThat(conflict.requestedGroupId()).isEqualTo(groupId);
        });
        verify(repository).lockRule(ruleId);
    }

    @Test
    void groupAssignmentWithDisjointMemberKindsDoesNotThrow() {
        UUID ruleId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(repository.kindOfRule(ruleId)).thenReturn(Optional.of(COUNT_DAY_PHONE_IN));
        when(repository.kindsReceivedByMembersOfGroup(groupId, AT))
                .thenReturn(List.of(new MemberOtherGroupKind("502118", UUID.randomUUID(), DISJOINT_AMOUNT_MONTH)));

        assertThatCode(() -> checker.checkGroupAssignment(ruleId, groupId, AT)).doesNotThrowAnyException();
        verify(repository).lockRule(ruleId);
    }

    @Test
    void groupAssignmentShortCircuitsWhenRuleMissing() {
        UUID ruleId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(repository.kindOfRule(ruleId)).thenReturn(Optional.empty());

        assertThatCode(() -> checker.checkGroupAssignment(ruleId, groupId, AT)).doesNotThrowAnyException();
        verify(repository).lockRule(ruleId);
    }

    // ---- rule activation (checkpoint c) ----

    @Test
    void ruleActivationConflictAcrossItsGroupsThrows() {
        UUID ruleId = UUID.randomUUID();
        UUID assignedGroup = UUID.randomUUID();
        UUID otherGroup = UUID.randomUUID();
        when(repository.kindOfRule(ruleId)).thenReturn(Optional.of(COUNT_DAY_PHONE_IN));
        when(repository.groupsWithEnabledAssignmentForRule(ruleId, AT)).thenReturn(List.of(assignedGroup));
        when(repository.kindsReceivedByMembersOfGroup(assignedGroup, AT))
                .thenReturn(List.of(new MemberOtherGroupKind("502118", otherGroup, COUNT_DAY_PHONE_IN)));

        assertThatThrownBy(() -> checker.checkRuleActivation(ruleId, AT))
                .isInstanceOf(LimitKindConflictException.class);
        verify(repository).lockRule(ruleId);
    }

    @Test
    void ruleActivationWithoutGroupAssignmentsDoesNotThrow() {
        UUID ruleId = UUID.randomUUID();
        when(repository.kindOfRule(ruleId)).thenReturn(Optional.of(COUNT_DAY_PHONE_IN));
        when(repository.groupsWithEnabledAssignmentForRule(ruleId, AT)).thenReturn(List.of());

        assertThatCode(() -> checker.checkRuleActivation(ruleId, AT)).doesNotThrowAnyException();
        verify(repository).lockRule(ruleId);
        verify(repository, org.mockito.Mockito.never()).kindsReceivedByMembersOfGroup(any(), any());
    }

    // ---- snapshot re-check (compilation, checkpoint d) ----

    @Test
    void snapshotConflictBetweenTwoGroupsSharingAMemberIsDetected() {
        String merchantId = "700001";
        UUID ruleA = UUID.randomUUID();
        UUID ruleB = UUID.randomUUID();
        // Two groups ordered by UUID string so the assertion on existing/requested is deterministic.
        UUID groupLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID groupHigh = UUID.fromString("00000000-0000-0000-0000-000000000002");

        List<LimitKindConflict> conflicts = LimitKindInvariantChecker.findSnapshotConflicts(
                List.of(new SnapshotMembership(merchantId, groupLow), new SnapshotMembership(merchantId, groupHigh)),
                List.of(new SnapshotGroupAssignment(groupLow, ruleA), new SnapshotGroupAssignment(groupHigh, ruleB)),
                Map.of(ruleA, COUNT_DAY_PHONE_IN, ruleB, COUNT_DAY_PHONE_IN));

        assertThat(conflicts).singleElement().satisfies(conflict -> {
            assertThat(conflict.merchantId()).isEqualTo(merchantId);
            assertThat(conflict.limitKind().checkType()).isEqualTo("COUNT_DAY");
            assertThat(conflict.existingGroupId()).isEqualTo(groupLow);
            assertThat(conflict.requestedGroupId()).isEqualTo(groupHigh);
        });
    }

    @Test
    void snapshotDisjointKindsFromTwoGroupsProduceNoConflict() {
        String merchantId = "700002";
        UUID ruleA = UUID.randomUUID();
        UUID ruleB = UUID.randomUUID();
        UUID groupA = UUID.randomUUID();
        UUID groupB = UUID.randomUUID();

        List<LimitKindConflict> conflicts = LimitKindInvariantChecker.findSnapshotConflicts(
                List.of(new SnapshotMembership(merchantId, groupA), new SnapshotMembership(merchantId, groupB)),
                List.of(new SnapshotGroupAssignment(groupA, ruleA), new SnapshotGroupAssignment(groupB, ruleB)),
                Map.of(ruleA, COUNT_DAY_PHONE_IN, ruleB, DISJOINT_AMOUNT_MONTH));

        assertThat(conflicts).isEmpty();
    }

    @Test
    void snapshotSameKindFromASingleGroupIsNotAConflict() {
        String merchantId = "700003";
        UUID ruleA = UUID.randomUUID();
        UUID onlyGroup = UUID.randomUUID();

        List<LimitKindConflict> conflicts = LimitKindInvariantChecker.findSnapshotConflicts(
                List.of(new SnapshotMembership(merchantId, onlyGroup)),
                List.of(new SnapshotGroupAssignment(onlyGroup, ruleA)),
                Map.of(ruleA, COUNT_DAY_PHONE_IN));

        assertThat(conflicts).isEmpty();
    }

    @Test
    void snapshotIgnoresAssignmentsOfRulesAbsentFromTheSnapshot() {
        String merchantId = "700004";
        UUID activeRule = UUID.randomUUID();
        UUID inactiveRule = UUID.randomUUID();
        UUID groupA = UUID.randomUUID();
        UUID groupB = UUID.randomUUID();

        // groupB delivers a rule that is not part of the (active) snapshot -> no kind, no conflict.
        List<LimitKindConflict> conflicts = LimitKindInvariantChecker.findSnapshotConflicts(
                List.of(new SnapshotMembership(merchantId, groupA), new SnapshotMembership(merchantId, groupB)),
                List.of(new SnapshotGroupAssignment(groupA, activeRule), new SnapshotGroupAssignment(groupB, inactiveRule)),
                Map.of(activeRule, COUNT_DAY_PHONE_IN));

        assertThat(conflicts).isEmpty();
    }
}
