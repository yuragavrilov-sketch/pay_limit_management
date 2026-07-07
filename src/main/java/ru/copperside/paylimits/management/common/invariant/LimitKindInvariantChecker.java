package ru.copperside.paylimits.management.common.invariant;

import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository;
import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository.MerchantGroupKind;
import ru.copperside.paylimits.management.limitrule.domain.LimitKind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Last-line-of-defence check performed over a compiled runtime-manifest snapshot (spec §3.4,
     * MGT-I-09). The three interactive checkpoints hold an advisory lock and cannot be bypassed by a
     * single request, but a snapshot assembled from independently-valid writes (or data seeded around
     * the API) can still contain an overlap. This pure function re-derives, per merchant, the limit
     * kinds it receives via its groups' MERCHANT_GROUP assignments of active rules and reports every
     * conflicting pair delivered from two <em>different</em> groups.
     *
     * <p>Operates purely on the data already loaded for compilation — it issues no queries — so the
     * caller must pass only ACTIVE rules and enabled MERCHANT_GROUP assignments, mirroring the scope
     * of the interactive checks. Output is deterministic: conflicts are de-duplicated and ordered by
     * merchant, then existing group, then requested group; within a conflicting pair the two groups
     * are ordered by UUID (lower = {@code existingGroupId}).
     *
     * @param memberships      merchant → group edges from the snapshot
     * @param groupAssignments group → ruleId edges (MERCHANT_GROUP assignments) from the snapshot
     * @param ruleKinds        ruleId → {@link LimitKind} for the active rules in the snapshot
     * @return conflicting pairs, empty when the snapshot honours the invariant
     */
    public static List<LimitKindConflict> findSnapshotConflicts(
            List<SnapshotMembership> memberships,
            List<SnapshotGroupAssignment> groupAssignments,
            Map<UUID, LimitKind> ruleKinds) {

        Map<UUID, List<LimitKind>> kindsByGroup = new HashMap<>();
        for (SnapshotGroupAssignment assignment : groupAssignments) {
            LimitKind kind = ruleKinds.get(assignment.ruleId());
            if (kind == null) {
                continue; // rule absent from the snapshot (not active) — out of scope
            }
            kindsByGroup.computeIfAbsent(assignment.groupId(), key -> new ArrayList<>()).add(kind);
        }

        Map<String, Set<UUID>> groupsByMerchant = new HashMap<>();
        for (SnapshotMembership membership : memberships) {
            groupsByMerchant
                    .computeIfAbsent(membership.merchantId(), key -> new LinkedHashSet<>())
                    .add(membership.groupId());
        }

        Set<LimitKindConflict> conflicts = new LinkedHashSet<>();
        for (Map.Entry<String, Set<UUID>> entry : groupsByMerchant.entrySet()) {
            String merchantId = entry.getKey();
            List<UUID> groups = entry.getValue().stream()
                    .sorted(Comparator.comparing(UUID::toString))
                    .toList();
            for (int i = 0; i < groups.size(); i++) {
                UUID left = groups.get(i);
                List<LimitKind> leftKinds = kindsByGroup.getOrDefault(left, List.of());
                if (leftKinds.isEmpty()) {
                    continue;
                }
                for (int j = i + 1; j < groups.size(); j++) {
                    UUID right = groups.get(j);
                    for (LimitKind leftKind : leftKinds) {
                        for (LimitKind rightKind : kindsByGroup.getOrDefault(right, List.of())) {
                            if (leftKind.conflictsWith(rightKind)) {
                                conflicts.add(new LimitKindConflict(
                                        merchantId, LimitKindView.of(leftKind), left, right));
                            }
                        }
                    }
                }
            }
        }

        return conflicts.stream()
                .sorted(Comparator.comparing(LimitKindConflict::merchantId)
                        .thenComparing(conflict -> conflict.existingGroupId().toString())
                        .thenComparing(conflict -> conflict.requestedGroupId().toString()))
                .toList();
    }

    /** A merchant → merchant-group edge in a compiled snapshot. */
    public record SnapshotMembership(String merchantId, UUID groupId) {
    }

    /** A merchant-group → rule edge (an enabled MERCHANT_GROUP assignment) in a compiled snapshot. */
    public record SnapshotGroupAssignment(UUID groupId, UUID ruleId) {
    }
}
