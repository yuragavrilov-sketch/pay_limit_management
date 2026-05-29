package ru.copperside.paylimits.management.limitassignment.application.port.out;

import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignment;
import ru.copperside.paylimits.management.limitassignment.domain.MerchantGroupReference;
import ru.copperside.paylimits.management.limitassignment.domain.RuleReference;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LimitAssignmentRepository {
    List<LimitAssignment> listAssignments();

    Optional<LimitAssignment> findAssignment(UUID assignmentId);

    Optional<RuleReference> findRule(UUID ruleId);

    Optional<MerchantGroupReference> findMerchantGroup(UUID groupId);

    boolean hasEnabledOverlap(
            UUID excludedAssignmentId,
            UUID ruleId,
            AssignmentOwnerType ownerType,
            String ownerId,
            Instant validFrom,
            Instant validTo
    );

    LimitAssignment saveAssignment(LimitAssignment assignment);

    LimitAssignment updateAssignment(LimitAssignment assignment);
}
