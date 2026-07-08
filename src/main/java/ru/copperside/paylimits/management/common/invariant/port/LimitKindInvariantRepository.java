package ru.copperside.paylimits.management.common.invariant.port;

import ru.copperside.paylimits.management.limitrule.domain.LimitKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port supplying the data the limit-kind non-overlap invariant checker needs
 * (spec §2, techspec §6): the kinds a group delivers, group membership, and advisory-lock
 * primitives that serialize concurrent invariant checks.
 *
 * <p>The lock methods only take effect inside an existing transaction (advisory <b>xact</b>
 * locks release at transaction end) — the transaction boundary is owned by the invariant-check
 * use case, not this port.
 */
public interface LimitKindInvariantRepository {

    /**
     * Serializes concurrent invariant checks that touch the same merchant (membership changes).
     */
    void lockMerchant(String merchantId);

    /**
     * Serializes concurrent invariant checks that touch the same rule (assignment/activation
     * changes).
     */
    void lockRule(UUID ruleId);

    /**
     * The distinct {@link LimitKind}s delivered to merchants by a group as of {@code at}, derived from
     * that group's enabled {@code MERCHANT_GROUP} assignments of {@code ACTIVE} rules whose validity
     * window contains {@code at} ({@code valid_from <= at and (valid_to is null or valid_to > at)}).
     * The invariant is temporal: an assignment only delivers its kind while it is in effect.
     */
    List<LimitKind> kindsDeliveredByGroup(UUID groupId, Instant at);

    /**
     * The merchant ids that are current-or-future members of a group as of {@code at}
     * ({@code valid_to is null or valid_to > at}), distinct.
     */
    List<String> membersOfGroup(UUID groupId, Instant at);

    /**
     * For every group (other than {@code excludedGroupId}) the merchant is a current-or-future
     * member of as of {@code at}, the {@link LimitKind}s that group delivers, each tagged with
     * its group id. Only enabled group assignments of ACTIVE rules whose own validity window
     * contains {@code at} ({@code valid_from <= at and (valid_to is null or valid_to > at)}) are
     * considered — an expired-but-enabled assignment no longer delivers its kind.
     */
    List<MerchantGroupKind> kindsReceivedByMerchantExcludingGroup(String merchantId, UUID excludedGroupId, Instant at);

    /**
     * As {@link #kindsReceivedByMerchantExcludingGroup(String, UUID, Instant)} but excludes a SET of
     * groups. Used by the membership move flow, where both the requested group and the same-type
     * predecessor group (which is closed and replaced in the same transaction) must be excluded from
     * the merchant's currently-received kinds.
     */
    List<MerchantGroupKind> kindsReceivedByMerchantExcludingGroups(
            String merchantId, java.util.Collection<UUID> excludedGroupIds, Instant at);

    /**
     * The groups that have an enabled {@code MERCHANT_GROUP} assignment of the given rule whose
     * validity window contains {@code at} ({@code valid_from <= at and (valid_to is null or
     * valid_to > at)}). An expired assignment no longer delivers its kind, so it does not participate.
     */
    List<UUID> groupsWithEnabledAssignmentForRule(UUID ruleId, Instant at);

    /**
     * The {@link LimitKind} of a rule by id (any status), or empty if the rule does not exist.
     */
    Optional<LimitKind> kindOfRule(UUID ruleId);

    /**
     * For every merchant who is a current-or-future member of {@code groupId} as of {@code at}
     * ({@code valid_to is null or valid_to > at}), the {@link LimitKind}s delivered to them by
     * OTHER groups (group id different from {@code groupId}) they also belong to at {@code at}.
     * Only enabled {@code MERCHANT_GROUP} assignments of {@code ACTIVE} rules whose own validity
     * window contains {@code at} ({@code valid_from <= at and (valid_to is null or valid_to > at)})
     * are considered, mirroring {@link #kindsReceivedByMerchantExcludingGroup(String, UUID, Instant)}.
     *
     * <p>Single round-trip replacement for looping {@link #membersOfGroup(UUID, Instant)} and
     * calling {@link #kindsReceivedByMerchantExcludingGroup(String, UUID, Instant)} once per member —
     * used by the group-assignment and rule-activation invariant checkpoints, which run under the
     * rule advisory lock.
     */
    List<MemberOtherGroupKind> kindsReceivedByMembersOfGroup(UUID groupId, Instant at);

    record MerchantGroupKind(UUID groupId, LimitKind kind) {
    }

    record MemberOtherGroupKind(String merchantId, UUID otherGroupId, LimitKind kind) {
    }
}
