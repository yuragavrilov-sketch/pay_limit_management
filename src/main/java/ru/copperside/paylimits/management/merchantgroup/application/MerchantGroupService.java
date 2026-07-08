package ru.copperside.paylimits.management.merchantgroup.application;

import ru.copperside.paylimits.management.audit.application.AuditRecorder;
import ru.copperside.paylimits.management.common.application.RequestValidation;
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

    private static final String ENTITY_GROUP_TYPE = "MERCHANT_GROUP_TYPE";
    private static final String ENTITY_GROUP = "MERCHANT_GROUP";
    private static final String ENTITY_MEMBERSHIP = "MERCHANT_GROUP_MEMBERSHIP";

    private final MerchantGroupRepository repository;
    private final LimitKindInvariantChecker invariantChecker;
    private final TransactionRunner transactionRunner;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public MerchantGroupService(
            MerchantGroupRepository repository,
            LimitKindInvariantChecker invariantChecker,
            TransactionRunner transactionRunner,
            AuditRecorder auditRecorder,
            Clock clock
    ) {
        this.repository = repository;
        this.invariantChecker = invariantChecker;
        this.transactionRunner = transactionRunner;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    public MerchantGroupType createType(CreateGroupTypeCommand command) {
        requireCommand(command);
        Instant now = Instant.now(clock);
        MerchantGroupType type = new MerchantGroupType(
                UUID.randomUUID(),
                requireText(command.code(), "code"),
                requireText(command.name(), "name"),
                blankToNull(command.description()),
                true,
                command.sortOrder(),
                now,
                now
        );
        return transactionRunner.run(() -> auditRecorder.writeAndRecord(
                ENTITY_GROUP_TYPE, "CREATE", null, e -> e.id().toString(),
                () -> repository.saveType(type)));
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
        return transactionRunner.run(() -> auditRecorder.writeAndRecord(
                ENTITY_GROUP_TYPE, "UPDATE", existing, e -> e.id().toString(),
                () -> repository.updateType(updated)));
    }

    public MerchantGroup createGroup(CreateGroupCommand command) {
        requireCommand(command);
        MerchantGroupType type = repository.findType(requireUuid(command.typeId(), "typeId"))
                .orElseThrow(() -> problem("GROUP_TYPE_NOT_FOUND", "Group type not found"));
        if (!type.enabled()) {
            throw problem("GROUP_TYPE_DISABLED", "Group type is disabled");
        }
        Instant now = Instant.now(clock);
        MerchantGroup group = new MerchantGroup(
                UUID.randomUUID(),
                type.id(),
                requireText(command.code(), "code"),
                requireText(command.name(), "name"),
                blankToNull(command.description()),
                true,
                now,
                now
        );
        return transactionRunner.run(() -> auditRecorder.writeAndRecord(
                ENTITY_GROUP, "CREATE", null, e -> e.id().toString(),
                () -> repository.saveGroup(group)));
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
        return transactionRunner.run(() -> auditRecorder.writeAndRecord(
                ENTITY_GROUP, "UPDATE", existing, e -> e.id().toString(),
                () -> repository.updateGroup(updated)));
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
            // The invariant is temporal: two group-limit-kinds conflict only if they are in effect at
            // the same time. Check "at" the new membership's validFrom (spec §8 MGT-I-19), not "now" --
            // otherwise a future-dated, non-overlapping change against a not-yet-closed predecessor
            // membership in a DIFFERENT group produces a false 409.
            invariantChecker.checkMembershipUnderLock(merchantId, group.id(), replacedGroups, validFrom);
            MerchantGroupMembership saved = overlapping
                    .map(existing -> replaceExistingMembership(existing, validFrom, now, actor, membership))
                    .orElseGet(() -> repository.saveMembership(membership));
            // A tier move (predecessor's group differs from the requested one) closes the predecessor
            // row as a side effect of replaceExistingMembership -- that closure is a real mutation and
            // must be audited the same way an explicit closeMembership() call would be, in the same
            // transaction as the ASSIGN_MEMBERSHIP event for the new row.
            overlapping
                    .filter(existing -> !existing.groupId().equals(group.id()))
                    .ifPresent(existing -> {
                        MerchantGroupMembership closedPredecessor = existing.close(validFrom, now, actor);
                        auditRecorder.record(
                                ENTITY_MEMBERSHIP, closedPredecessor.id().toString(), "CLOSE_MEMBERSHIP", existing, closedPredecessor);
                    });
            auditRecorder.record(ENTITY_MEMBERSHIP, saved.id().toString(), "ASSIGN_MEMBERSHIP", null, saved);
            return saved;
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
        return transactionRunner.run(() -> {
            MerchantGroupMembership closed = repository.closeMembership(membershipId, validTo, Instant.now(clock), actor);
            auditRecorder.record(ENTITY_MEMBERSHIP, closed.id().toString(), "CLOSE_MEMBERSHIP", membership, closed);
            return closed;
        });
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
        RequestValidation.requireCommand(command, this::problem);
    }

    private UUID requireUuid(UUID value, String field) {
        return RequestValidation.requireUuid(value, field, this::problem);
    }

    private Instant requireInstant(Instant value, String field) {
        return RequestValidation.requireInstant(value, field, this::problem);
    }

    private String requireText(String value, String field) {
        return RequestValidation.requireText(value, field, this::problem);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private MerchantGroupProblemException problem(String code, String message) {
        return new MerchantGroupProblemException(code, message);
    }
}
