package ru.copperside.paylimits.management.common.invariant;

import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository;
import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository.MerchantGroupKind;
import ru.copperside.paylimits.management.limitrule.domain.LimitKind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Enforces the limit-kind non-overlap invariant (spec §2.3): a merchant must never receive the
 * same {@link LimitKind} from more than one merchant group. Applied at the three write points that
 * can introduce such an overlap — adding a membership, assigning a rule to a group, and activating
 * a rule that has group assignments.
 *
 * <p>Each method first takes the relevant advisory lock through the port and then runs the conflict
 * queries. Because {@code pg_advisory_xact_lock} releases at transaction end, callers MUST invoke
 * these methods inside the same transaction as the subsequent write (see
 * {@link ru.copperside.paylimits.management.common.invariant.port.TransactionRunner}); otherwise
 * two concurrent requests can each pass the check and both write.
 */
public class LimitKindInvariantChecker {

    private final LimitKindInvariantRepository repository;

    public LimitKindInvariantChecker(LimitKindInvariantRepository repository) {
        this.repository = repository;
    }

    /**
     * Checkpoint (a): a merchant is about to join {@code requestedGroupId}. Rejects if any kind that
     * group delivers conflicts with a kind the merchant already receives from another group.
     */
    public void checkMembership(String merchantId, UUID requestedGroupId, Instant at) {
        repository.lockMerchant(merchantId);
        List<LimitKind> delivered = repository.kindsDeliveredByGroup(requestedGroupId);
        if (delivered.isEmpty()) {
            return;
        }
        List<MerchantGroupKind> received =
                repository.kindsReceivedByMerchantExcludingGroup(merchantId, requestedGroupId, at);
        List<LimitKindConflict> conflicts = new ArrayList<>();
        for (LimitKind deliveredKind : delivered) {
            for (MerchantGroupKind other : received) {
                if (deliveredKind.conflictsWith(other.kind())) {
                    conflicts.add(new LimitKindConflict(
                            merchantId, LimitKindView.of(deliveredKind), other.groupId(), requestedGroupId));
                }
            }
        }
        throwIfConflicting(conflicts);
    }

    /**
     * Checkpoint (b): a rule is about to be assigned to {@code groupId}. Rejects if the rule's kind
     * conflicts with a kind any member of the group already receives from another group.
     */
    public void checkGroupAssignment(UUID ruleId, UUID groupId, Instant at) {
        repository.lockRule(ruleId);
        Optional<LimitKind> ruleKind = repository.kindOfRule(ruleId);
        if (ruleKind.isEmpty()) {
            return;
        }
        throwIfConflicting(collectGroupConflicts(ruleKind.get(), groupId, at));
    }

    /**
     * Checkpoint (c): a rule is being activated. Rejects if, for any group that already has an
     * enabled assignment of the rule, the rule's kind conflicts with a kind any member of that
     * group receives from another group.
     */
    public void checkRuleActivation(UUID ruleId, Instant at) {
        repository.lockRule(ruleId);
        Optional<LimitKind> ruleKind = repository.kindOfRule(ruleId);
        if (ruleKind.isEmpty()) {
            return;
        }
        List<LimitKindConflict> conflicts = new ArrayList<>();
        for (UUID groupId : repository.groupsWithEnabledAssignmentForRule(ruleId)) {
            conflicts.addAll(collectGroupConflicts(ruleKind.get(), groupId, at));
        }
        throwIfConflicting(conflicts);
    }

    private List<LimitKindConflict> collectGroupConflicts(LimitKind ruleKind, UUID groupId, Instant at) {
        List<LimitKindConflict> conflicts = new ArrayList<>();
        for (String merchantId : repository.membersOfGroup(groupId, at)) {
            for (MerchantGroupKind other : repository.kindsReceivedByMerchantExcludingGroup(merchantId, groupId, at)) {
                if (ruleKind.conflictsWith(other.kind())) {
                    conflicts.add(new LimitKindConflict(
                            merchantId, LimitKindView.of(ruleKind), other.groupId(), groupId));
                }
            }
        }
        return conflicts;
    }

    private void throwIfConflicting(List<LimitKindConflict> conflicts) {
        if (!conflicts.isEmpty()) {
            throw new LimitKindConflictException(conflicts, false);
        }
    }
}
