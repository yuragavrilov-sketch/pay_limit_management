package ru.copperside.paylimits.management.limitassignment.application;

import ru.copperside.paylimits.management.audit.application.AuditRecorder;
import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;
import ru.copperside.paylimits.management.limitassignment.application.port.out.LimitAssignmentRepository;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignment;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignmentProblemException;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitassignment.domain.MerchantGroupReference;
import ru.copperside.paylimits.management.limitassignment.domain.RuleReference;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LimitAssignmentService {

    private static final String ENTITY_ASSIGNMENT = "LIMIT_ASSIGNMENT";

    private final LimitAssignmentRepository repository;
    private final LimitKindInvariantChecker invariantChecker;
    private final TransactionRunner transactionRunner;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public LimitAssignmentService(
            LimitAssignmentRepository repository,
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

    public List<LimitAssignment> listAssignments() {
        return repository.listAssignments();
    }

    public LimitAssignment createAssignment(CreateLimitAssignmentCommand command) {
        requireCommand(command);
        UUID ruleId = requireUuid(command.ruleId(), "ruleId");
        AssignmentOwnerType ownerType = requireEnum(command.ownerType(), "ownerType");
        String ownerId = validateOwner(ownerType, command.ownerId());
        validateRule(ruleId);
        LimitMode limitMode = requireEnum(command.limitMode(), "limitMode");
        Instant validFrom = requireInstant(command.validFrom(), "validFrom");
        Instant validTo = validatePeriod(validFrom, command.validTo());

        Instant now = Instant.now(clock);
        LimitAssignment assignment = new LimitAssignment(
                UUID.randomUUID(),
                ruleId,
                ownerType,
                ownerId,
                limitMode,
                validFrom,
                validTo,
                true,
                now,
                now
        );

        // Lock (by rule), the non-overlap invariant check, and the assignment write share a single
        // transaction so the advisory lock actually serializes concurrent assignment/activation
        // changes for the same rule. The limit-kind invariant only applies to GROUP-level
        // assignments; GLOBAL/MERCHANT assignments skip it.
        return transactionRunner.run(() -> {
            if (ownerType == AssignmentOwnerType.MERCHANT_GROUP) {
                // The invariant is temporal: check "at" the new assignment's validFrom (spec §8
                // MGT-I-19), not "now" -- otherwise a future-dated assignment can be falsely rejected
                // against a group membership that will have ended by the time the assignment takes
                // effect.
                invariantChecker.checkGroupAssignment(ruleId, UUID.fromString(ownerId), validFrom);
            }
            rejectOverlap(null, ruleId, ownerType, ownerId, validFrom, validTo, true);
            LimitAssignment saved = repository.saveAssignment(assignment);
            auditRecorder.record(ENTITY_ASSIGNMENT, saved.id().toString(), "CREATE", null, saved);
            return saved;
        });
    }

    public LimitAssignment patchAssignment(UUID assignmentId, PatchLimitAssignmentCommand command) {
        requireCommand(command);
        LimitAssignment existing = repository.findAssignment(requireUuid(assignmentId, "assignmentId"))
                .orElseThrow(() -> problem("ASSIGNMENT_NOT_FOUND", "Assignment not found"));
        LimitMode limitMode = command.limitMode() == null ? existing.limitMode() : command.limitMode();
        Instant validFrom = command.validFrom() == null ? existing.validFrom() : command.validFrom();
        Instant validTo = command.validTo() == null ? existing.validTo() : command.validTo();
        boolean enabled = command.enabled() == null ? existing.enabled() : command.enabled();
        validatePeriod(validFrom, validTo);
        rejectOverlap(existing.id(), existing.ruleId(), existing.ownerType(), existing.ownerId(), validFrom, validTo, enabled);

        LimitAssignment updated = new LimitAssignment(
                existing.id(),
                existing.ruleId(),
                existing.ownerType(),
                existing.ownerId(),
                limitMode,
                validFrom,
                validTo,
                enabled,
                existing.createdAt(),
                Instant.now(clock)
        );
        return transactionRunner.run(() -> {
            LimitAssignment saved = repository.updateAssignment(updated);
            auditRecorder.record(ENTITY_ASSIGNMENT, saved.id().toString(), "UPDATE", existing, saved);
            return saved;
        });
    }

    public LimitAssignment disableAssignment(UUID assignmentId) {
        LimitAssignment existing = repository.findAssignment(requireUuid(assignmentId, "assignmentId"))
                .orElseThrow(() -> problem("ASSIGNMENT_NOT_FOUND", "Assignment not found"));
        LimitAssignment updated = new LimitAssignment(
                existing.id(),
                existing.ruleId(),
                existing.ownerType(),
                existing.ownerId(),
                existing.limitMode(),
                existing.validFrom(),
                existing.validTo(),
                false,
                existing.createdAt(),
                Instant.now(clock)
        );
        return transactionRunner.run(() -> {
            LimitAssignment saved = repository.updateAssignment(updated);
            auditRecorder.record(ENTITY_ASSIGNMENT, saved.id().toString(), "DISABLE", existing, saved);
            return saved;
        });
    }

    private void validateRule(UUID ruleId) {
        RuleReference rule = repository.findRule(ruleId)
                .orElseThrow(() -> problem("RULE_NOT_FOUND", "Rule not found"));
        if (!rule.active()) {
            throw problem("RULE_STATUS_CONFLICT", "Only active rules can be assigned");
        }
    }

    private String validateOwner(AssignmentOwnerType ownerType, String ownerId) {
        if (ownerType == AssignmentOwnerType.GLOBAL) {
            if (ownerId != null && !ownerId.isBlank()) {
                throw problem("VALIDATION_ERROR", "ownerId must be absent for GLOBAL assignments");
            }
            return null;
        }
        String normalized = requireText(ownerId, "ownerId");
        if (ownerType != AssignmentOwnerType.MERCHANT_GROUP) {
            return normalized;
        }
        UUID groupId;
        try {
            groupId = UUID.fromString(normalized);
        } catch (IllegalArgumentException ex) {
            throw problem("VALIDATION_ERROR", "ownerId must be UUID for MERCHANT_GROUP");
        }
        MerchantGroupReference group = repository.findMerchantGroup(groupId)
                .orElseThrow(() -> problem("GROUP_NOT_FOUND", "Group not found"));
        if (!group.enabled()) {
            throw problem("GROUP_DISABLED", "Group is disabled");
        }
        return group.id().toString();
    }

    private Instant validatePeriod(Instant validFrom, Instant validTo) {
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw problem("VALIDATION_ERROR", "validTo must be after validFrom");
        }
        return validTo;
    }

    private void rejectOverlap(
            UUID excludedAssignmentId,
            UUID ruleId,
            AssignmentOwnerType ownerType,
            String ownerId,
            Instant validFrom,
            Instant validTo,
            boolean enabled
    ) {
        if (!enabled) {
            return;
        }
        if (repository.hasEnabledOverlap(excludedAssignmentId, ruleId, ownerType, ownerId, validFrom, validTo)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("ruleId", ruleId);
            details.put("ownerType", ownerType);
            details.put("ownerId", ownerId);
            throw problem("ASSIGNMENT_CONFLICT", "Enabled assignments for the same rule and owner must not overlap",
                    details);
        }
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

    private <T> T requireEnum(T value, String field) {
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

    private LimitAssignmentProblemException problem(String code, String message) {
        return new LimitAssignmentProblemException(code, message);
    }

    private LimitAssignmentProblemException problem(String code, String message, Object details) {
        return new LimitAssignmentProblemException(code, message, details);
    }
}
