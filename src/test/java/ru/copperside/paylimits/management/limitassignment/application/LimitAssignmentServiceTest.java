package ru.copperside.paylimits.management.limitassignment.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.copperside.paylimits.management.limitassignment.application.port.out.LimitAssignmentRepository;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignment;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignmentProblemException;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitassignment.domain.MerchantGroupReference;
import ru.copperside.paylimits.management.limitassignment.domain.RuleReference;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitAssignmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-29T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private FakeRepository repository;
    private LimitAssignmentService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        service = new LimitAssignmentService(
                repository,
                ru.copperside.paylimits.management.common.invariant.InvariantTestSupport.noOpChecker(),
                new ru.copperside.paylimits.management.common.invariant.InvariantTestSupport.PassThroughTransactionRunner(),
                CLOCK);
    }

    @Test
    void createsLimitedAssignmentForActiveRuleAndEnabledGroup() {
        UUID ruleId = repository.addRule(true);
        UUID groupId = repository.addMerchantGroup(true);
        Instant validFrom = Instant.parse("2026-05-29T12:00:00Z");

        LimitAssignment assignment = service.createAssignment(new CreateLimitAssignmentCommand(
                ruleId,
                AssignmentOwnerType.MERCHANT_GROUP,
                groupId.toString(),
                LimitMode.LIMITED,
                validFrom,
                null
        ));

        assertThat(assignment.id()).isNotNull();
        assertThat(assignment.ruleId()).isEqualTo(ruleId);
        assertThat(assignment.ownerType()).isEqualTo(AssignmentOwnerType.MERCHANT_GROUP);
        assertThat(assignment.ownerId()).isEqualTo(groupId.toString());
        assertThat(assignment.limitMode()).isEqualTo(LimitMode.LIMITED);
        assertThat(assignment.validFrom()).isEqualTo(validFrom);
        assertThat(assignment.validTo()).isNull();
        assertThat(assignment.enabled()).isTrue();
        assertThat(assignment.createdAt()).isEqualTo(NOW);
        assertThat(assignment.updatedAt()).isEqualTo(NOW);
        assertThat(repository.assignments).containsExactly(assignment);
    }

    @Test
    void createsUnlimitedAssignmentForMerchant() {
        UUID ruleId = repository.addRule(true);

        LimitAssignment assignment = service.createAssignment(new CreateLimitAssignmentCommand(
                ruleId,
                AssignmentOwnerType.MERCHANT,
                "502118",
                LimitMode.UNLIMITED,
                Instant.parse("2026-05-29T12:00:00Z"),
                null
        ));

        assertThat(assignment.ownerType()).isEqualTo(AssignmentOwnerType.MERCHANT);
        assertThat(assignment.ownerId()).isEqualTo("502118");
        assertThat(assignment.limitMode()).isEqualTo(LimitMode.UNLIMITED);
    }

    @Test
    void rejectsAssignmentForInactiveRule() {
        UUID draftRuleId = repository.addRule(false);

        assertThatThrownBy(() -> service.createAssignment(new CreateLimitAssignmentCommand(
                draftRuleId,
                AssignmentOwnerType.MERCHANT,
                "502118",
                LimitMode.UNLIMITED,
                Instant.parse("2026-05-29T12:00:00Z"),
                null
        )))
                .isInstanceOf(LimitAssignmentProblemException.class)
                .hasMessageContaining("RULE_STATUS_CONFLICT");
    }

    @Test
    void rejectsAssignmentToDisabledGroup() {
        UUID ruleId = repository.addRule(true);
        UUID groupId = repository.addMerchantGroup(false);

        assertThatThrownBy(() -> service.createAssignment(new CreateLimitAssignmentCommand(
                ruleId,
                AssignmentOwnerType.MERCHANT_GROUP,
                groupId.toString(),
                LimitMode.UNLIMITED,
                Instant.parse("2026-05-29T12:00:00Z"),
                null
        )))
                .isInstanceOf(LimitAssignmentProblemException.class)
                .hasMessageContaining("GROUP_DISABLED");
    }

    @Test
    void rejectsOverlappingEnabledAssignmentForSameRuleAndOwner() {
        UUID ruleId = repository.addRule(true);
        repository.addAssignment(ruleId, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.UNLIMITED,
                Instant.parse("2026-05-29T12:00:00Z"), null, true);

        assertThatThrownBy(() -> service.createAssignment(new CreateLimitAssignmentCommand(
                ruleId,
                AssignmentOwnerType.MERCHANT,
                "502118",
                LimitMode.BLOCKED,
                Instant.parse("2026-05-30T12:00:00Z"),
                null
        )))
                .isInstanceOf(LimitAssignmentProblemException.class)
                .hasMessageContaining("ASSIGNMENT_CONFLICT");
    }

    @Test
    void allowsAdjacentAssignmentPeriodsForSameRuleAndOwner() {
        UUID ruleId = repository.addRule(true);
        repository.addAssignment(ruleId, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.UNLIMITED,
                Instant.parse("2026-05-29T12:00:00Z"),
                Instant.parse("2026-05-30T12:00:00Z"),
                true);

        LimitAssignment next = service.createAssignment(new CreateLimitAssignmentCommand(
                ruleId,
                AssignmentOwnerType.MERCHANT,
                "502118",
                LimitMode.BLOCKED,
                Instant.parse("2026-05-30T12:00:00Z"),
                null
        ));

        assertThat(next.limitMode()).isEqualTo(LimitMode.BLOCKED);
    }

    @Test
    void patchesMutableFieldsAndPreservesIdentity() {
        UUID ruleId = repository.addRule(true);
        LimitAssignment existing = repository.addAssignment(ruleId, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.LIMITED,
                Instant.parse("2026-05-29T12:00:00Z"), null, true);

        LimitAssignment patched = service.patchAssignment(existing.id(), new PatchLimitAssignmentCommand(
                LimitMode.BLOCKED,
                Instant.parse("2026-05-30T12:00:00Z"),
                null,
                true
        ));

        assertThat(patched.id()).isEqualTo(existing.id());
        assertThat(patched.ruleId()).isEqualTo(existing.ruleId());
        assertThat(patched.ownerType()).isEqualTo(existing.ownerType());
        assertThat(patched.ownerId()).isEqualTo(existing.ownerId());
        assertThat(patched.limitMode()).isEqualTo(LimitMode.BLOCKED);
        assertThat(patched.validFrom()).isEqualTo(Instant.parse("2026-05-30T12:00:00Z"));
        assertThat(patched.enabled()).isTrue();
        assertThat(patched.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void disablesAssignmentWithoutDeletingIt() {
        UUID ruleId = repository.addRule(true);
        LimitAssignment existing = repository.addAssignment(ruleId, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.UNLIMITED,
                Instant.parse("2026-05-29T12:00:00Z"), null, true);

        LimitAssignment disabled = service.disableAssignment(existing.id());

        assertThat(disabled.id()).isEqualTo(existing.id());
        assertThat(disabled.enabled()).isFalse();
        assertThat(repository.assignments).contains(disabled);
    }

    static class FakeRepository implements LimitAssignmentRepository {

        final List<RuleReference> rules = new ArrayList<>();
        final List<MerchantGroupReference> groups = new ArrayList<>();
        final List<LimitAssignment> assignments = new ArrayList<>();

        UUID addRule(boolean active) {
            UUID id = UUID.randomUUID();
            rules.add(new RuleReference(id, active));
            return id;
        }

        UUID addMerchantGroup(boolean enabled) {
            UUID id = UUID.randomUUID();
            groups.add(new MerchantGroupReference(id, enabled));
            return id;
        }

        LimitAssignment addAssignment(
                UUID ruleId,
                AssignmentOwnerType ownerType,
                String ownerId,
                LimitMode mode,
                Instant validFrom,
                Instant validTo,
                boolean enabled
        ) {
            LimitAssignment assignment = new LimitAssignment(
                    UUID.randomUUID(),
                    ruleId,
                    ownerType,
                    ownerId,
                    mode,
                    validFrom,
                    validTo,
                    enabled,
                    Instant.EPOCH,
                    Instant.EPOCH
            );
            assignments.add(assignment);
            return assignment;
        }

        @Override
        public List<LimitAssignment> listAssignments() {
            return List.copyOf(assignments);
        }

        @Override
        public Optional<LimitAssignment> findAssignment(UUID assignmentId) {
            return assignments.stream().filter(assignment -> assignment.id().equals(assignmentId)).findFirst();
        }

        @Override
        public Optional<RuleReference> findRule(UUID ruleId) {
            return rules.stream().filter(rule -> rule.id().equals(ruleId)).findFirst();
        }

        @Override
        public Optional<MerchantGroupReference> findMerchantGroup(UUID groupId) {
            return groups.stream().filter(group -> group.id().equals(groupId)).findFirst();
        }

        @Override
        public boolean hasEnabledOverlap(
                UUID excludedAssignmentId,
                UUID ruleId,
                AssignmentOwnerType ownerType,
                String ownerId,
                Instant validFrom,
                Instant validTo
        ) {
            return assignments.stream()
                    .filter(LimitAssignment::enabled)
                    .filter(assignment -> excludedAssignmentId == null || !assignment.id().equals(excludedAssignmentId))
                    .filter(assignment -> assignment.ruleId().equals(ruleId))
                    .filter(assignment -> assignment.ownerType() == ownerType)
                    .filter(assignment -> assignment.ownerId().equals(ownerId))
                    .anyMatch(assignment -> overlaps(validFrom, validTo, assignment.validFrom(), assignment.validTo()));
        }

        @Override
        public LimitAssignment saveAssignment(LimitAssignment assignment) {
            assignments.add(assignment);
            return assignment;
        }

        @Override
        public LimitAssignment updateAssignment(LimitAssignment assignment) {
            assignments.replaceAll(existing -> existing.id().equals(assignment.id()) ? assignment : existing);
            return assignment;
        }

        private boolean overlaps(Instant leftFrom, Instant leftTo, Instant rightFrom, Instant rightTo) {
            boolean leftStartsBeforeRightEnds = rightTo == null || leftFrom.isBefore(rightTo);
            boolean rightStartsBeforeLeftEnds = leftTo == null || rightFrom.isBefore(leftTo);
            return leftStartsBeforeRightEnds && rightStartsBeforeLeftEnds;
        }
    }
}
