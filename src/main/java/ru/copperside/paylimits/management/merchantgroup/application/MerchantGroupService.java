package ru.copperside.paylimits.management.merchantgroup.application;

import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;
import ru.copperside.paylimits.management.merchantgroup.application.port.out.MerchantGroupRepository;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroup;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupMembership;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupProblemException;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupType;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MerchantGroupService {

    private final MerchantGroupRepository repository;
    private final LimitKindInvariantChecker invariantChecker;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    public MerchantGroupService(
            MerchantGroupRepository repository,
            LimitKindInvariantChecker invariantChecker,
            TransactionRunner transactionRunner,
            Clock clock
    ) {
        this.repository = repository;
        this.invariantChecker = invariantChecker;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
    }

    public MerchantGroupType createType(CreateGroupTypeCommand command) {
        requireCommand(command);
        Instant now = Instant.now(clock);
        return repository.saveType(new MerchantGroupType(
                UUID.randomUUID(),
                requireText(command.code(), "code"),
                requireText(command.name(), "name"),
                blankToNull(command.description()),
                true,
                command.sortOrder(),
                now,
                now
        ));
    }

    public List<MerchantGroupType> listTypes() {
        return repository.listTypes();
    }

    public MerchantGroupType updateType(UUID id, PatchGroupTypeCommand command) {
        requireCommand(command);
        MerchantGroupType existing = repository.findType(requireUuid(id, "typeId"))
                .orElseThrow(() -> problem("GROUP_TYPE_NOT_FOUND", "Group type not found"));
        MerchantGroupType updated = new MerchantGroupType(
                existing.id(),
                existing.code(),
                command.name() == null ? existing.name() : requireText(command.name(), "name"),
                command.description() == null ? existing.description() : blankToNull(command.description()),
                command.enabled() == null ? existing.enabled() : command.enabled(),
                command.sortOrder() == null ? existing.sortOrder() : command.sortOrder(),
                existing.createdAt(),
                Instant.now(clock)
        );
        return repository.updateType(updated);
    }

    public MerchantGroup createGroup(CreateGroupCommand command) {
        requireCommand(command);
        MerchantGroupType type = repository.findType(requireUuid(command.typeId(), "typeId"))
                .orElseThrow(() -> problem("GROUP_TYPE_NOT_FOUND", "Group type not found"));
        if (!type.enabled()) {
            throw problem("GROUP_TYPE_DISABLED", "Group type is disabled");
        }
        Instant now = Instant.now(clock);
        return repository.saveGroup(new MerchantGroup(
                UUID.randomUUID(),
                type.id(),
                requireText(command.code(), "code"),
                requireText(command.name(), "name"),
                blankToNull(command.description()),
                true,
                now,
                now
        ));
    }

    public List<MerchantGroup> listGroups(UUID typeId) {
        return repository.listGroups(typeId);
    }

    public MerchantGroup updateGroup(UUID id, PatchGroupCommand command) {
        requireCommand(command);
        MerchantGroup existing = repository.findGroup(requireUuid(id, "groupId"))
                .orElseThrow(() -> problem("GROUP_NOT_FOUND", "Group not found"));
        MerchantGroup updated = new MerchantGroup(
                existing.id(),
                existing.typeId(),
                existing.code(),
                command.name() == null ? existing.name() : requireText(command.name(), "name"),
                command.description() == null ? existing.description() : blankToNull(command.description()),
                command.enabled() == null ? existing.enabled() : command.enabled(),
                existing.createdAt(),
                Instant.now(clock)
        );
        return repository.updateGroup(updated);
    }

    public MerchantGroupMembership assignMembership(AssignMembershipCommand command) {
        requireCommand(command);
        MerchantGroup group = repository.findGroup(requireUuid(command.groupId(), "groupId"))
                .orElseThrow(() -> problem("GROUP_NOT_FOUND", "Group not found"));
        MerchantGroupType type = repository.findType(group.typeId())
                .orElseThrow(() -> problem("GROUP_TYPE_NOT_FOUND", "Group type not found"));
        if (!type.enabled()) {
            throw problem("GROUP_TYPE_DISABLED", "Group type is disabled");
        }
        if (!group.enabled()) {
            throw problem("GROUP_DISABLED", "Group is disabled");
        }

        String merchantId = requireText(command.merchantId(), "merchantId");
        String actor = requireText(command.actor(), "actor");
        Instant validFrom = requireInstant(command.validFrom(), "validFrom");
        Instant now = Instant.now(clock);
        MerchantGroupMembership membership = new MerchantGroupMembership(
                UUID.randomUUID(),
                merchantId,
                group.id(),
                type.id(),
                validFrom,
                null,
                now,
                actor,
                null,
                null
        );

        // Lock (by merchant), the non-overlap invariant check, and the membership write share a
        // single transaction so the advisory lock actually serializes concurrent membership changes
        // for the same merchant (advisory xact locks release at transaction end). Ordering matters:
        // lock -> read the same-type membership that would be replaced -> check (excluding both the
        // requested group and that soon-to-be-closed predecessor) -> write. A same-type tier move
        // (G1 -> G2) must not 409 against its own predecessor G1, which replaceExistingMembership
        // closes, while a conflict with a DIFFERENT group the merchant keeps must still 409.
        return transactionRunner.run(() -> {
            invariantChecker.lockMerchant(merchantId);
            Optional<MerchantGroupMembership> overlapping =
                    repository.findOverlappingMembership(merchantId, type.id(), validFrom);
            List<UUID> replacedGroups = overlapping
                    .map(MerchantGroupMembership::groupId)
                    .filter(existingGroupId -> !existingGroupId.equals(group.id()))
                    .map(List::of)
                    .orElseGet(List::of);
            invariantChecker.checkMembershipUnderLock(merchantId, group.id(), replacedGroups, now);
            return overlapping
                    .map(existing -> replaceExistingMembership(existing, validFrom, now, actor, membership))
                    .orElseGet(() -> repository.saveMembership(membership));
        });
    }

    public List<MerchantGroupMembership> listMemberships(MembershipQuery query) {
        requireCommand(query);
        String state = query.state() == null || query.state().isBlank() ? "current" : query.state();
        if (!state.equals("current") && !state.equals("history") && !state.equals("all")) {
            throw problem("VALIDATION_ERROR", "state must be current, history, or all");
        }
        return repository.listMemberships(
                blankToNull(query.merchantId()),
                query.typeId(),
                query.groupId(),
                state,
                Instant.now(clock)
        );
    }

    public MerchantGroupMembership closeMembership(CloseMembershipCommand command) {
        requireCommand(command);
        UUID membershipId = requireUuid(command.membershipId(), "membershipId");
        Instant validTo = requireInstant(command.validTo(), "validTo");
        String actor = requireText(command.actor(), "actor");
        MerchantGroupMembership membership = repository.findMembership(membershipId)
                .orElseThrow(() -> problem("MEMBERSHIP_NOT_FOUND", "Membership not found"));
        if (!validTo.isAfter(membership.validFrom())) {
            throw problem("INVALID_MEMBERSHIP_PERIOD", "validTo must be after validFrom");
        }
        return repository.closeMembership(membershipId, validTo, Instant.now(clock), actor);
    }

    private MerchantGroupMembership replaceExistingMembership(
            MerchantGroupMembership existing,
            Instant validFrom,
            Instant now,
            String actor,
            MerchantGroupMembership membership
    ) {
        if (existing.validFrom().isAfter(validFrom)) {
            throw problem("INVALID_MEMBERSHIP_PERIOD", "Assignment must not overlap future membership");
        }
        if (existing.validTo() != null || existing.closedAt() != null) {
            throw problem("INVALID_MEMBERSHIP_PERIOD", "Assignment must not rewrite closed membership history");
        }
        if (existing.groupId().equals(membership.groupId())) {
            return existing;
        }
        if (!validFrom.isAfter(existing.validFrom())) {
            throw problem("INVALID_MEMBERSHIP_PERIOD", "Replacement must start after existing membership");
        }
        return repository.replaceMembership(existing.id(), validFrom, now, actor, membership);
    }

    private void requireCommand(Object command) {
        if (command == null) {
            throw problem("VALIDATION_ERROR", "command must not be null");
        }
    }

    private UUID requireUuid(UUID value, String field) {
        if (value == null) {
            throw problem("VALIDATION_ERROR", field + " must not be null");
        }
        return value;
    }

    private Instant requireInstant(Instant value, String field) {
        if (value == null) {
            throw problem("VALIDATION_ERROR", field + " must not be null");
        }
        return value;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw problem("VALIDATION_ERROR", field + " must not be blank");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private MerchantGroupProblemException problem(String code, String message) {
        return new MerchantGroupProblemException(code, message);
    }
}
