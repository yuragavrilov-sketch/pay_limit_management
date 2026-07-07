package ru.copperside.paylimits.management.merchantgroup.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.copperside.paylimits.management.merchantgroup.application.port.out.MerchantGroupRepository;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroup;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupMembership;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupProblemException;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantGroupServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-27T09:00:00Z"), ZoneOffset.UTC);

    private FakeRepository repository;
    private MerchantGroupService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        service = new MerchantGroupService(
                repository,
                ru.copperside.paylimits.management.common.invariant.InvariantTestSupport.noOpChecker(),
                new ru.copperside.paylimits.management.common.invariant.InvariantTestSupport.PassThroughTransactionRunner(),
                CLOCK);
    }

    @Test
    void createsGroupTypeWithGeneratedIdAndAuditTimestamps() {
        MerchantGroupType type = service.createType(new CreateGroupTypeCommand(
                "risk-tier",
                "Risk tier",
                "Risk segmentation",
                10
        ));

        assertThat(type.id()).isNotNull();
        assertThat(type.code()).isEqualTo("risk-tier");
        assertThat(type.enabled()).isTrue();
        assertThat(type.createdAt()).isEqualTo(Instant.parse("2026-05-27T09:00:00Z"));
        assertThat(repository.types).containsExactly(type);
    }

    @Test
    void createsGroupInsideEnabledType() {
        MerchantGroupType type = repository.addType("risk-tier", true);

        MerchantGroup group = service.createGroup(new CreateGroupCommand(
                type.id(),
                "risk-high",
                "High risk",
                "High risk merchants"
        ));

        assertThat(group.typeId()).isEqualTo(type.id());
        assertThat(group.code()).isEqualTo("risk-high");
        assertThat(group.enabled()).isTrue();
    }

    @Test
    void rejectsGroupCreationForDisabledType() {
        MerchantGroupType type = repository.addType("risk-tier", false);

        assertThatThrownBy(() -> service.createGroup(new CreateGroupCommand(
                type.id(),
                "risk-high",
                "High risk",
                null
        )))
                .isInstanceOf(MerchantGroupProblemException.class)
                .hasMessageContaining("GROUP_TYPE_DISABLED");
    }

    @Test
    void assigningMerchantToAnotherGroupOfSameTypeClosesPreviousMembership() {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup low = repository.addGroup(type.id(), "risk-low", true);
        MerchantGroup high = repository.addGroup(type.id(), "risk-high", true);
        MerchantGroupMembership previous = repository.addMembership(
                "502118",
                low.id(),
                type.id(),
                Instant.parse("2026-05-26T09:00:00Z"),
                null
        );

        MerchantGroupMembership next = service.assignMembership(new AssignMembershipCommand(
                "502118",
                high.id(),
                Instant.parse("2026-05-27T10:00:00Z"),
                "alice"
        ));

        assertThat(next.groupId()).isEqualTo(high.id());
        assertThat(repository.closedMembershipId).isEqualTo(previous.id());
        assertThat(repository.closedValidTo).isEqualTo(Instant.parse("2026-05-27T10:00:00Z"));
        assertThat(repository.replacedMembershipId).isEqualTo(previous.id());
    }

    @Test
    void assigningMerchantToSameActiveGroupReturnsExistingMembership() {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup group = repository.addGroup(type.id(), "risk-high", true);
        MerchantGroupMembership previous = repository.addMembership(
                "502118",
                group.id(),
                type.id(),
                Instant.parse("2026-05-26T09:00:00Z"),
                null
        );

        MerchantGroupMembership next = service.assignMembership(new AssignMembershipCommand(
                "502118",
                group.id(),
                Instant.parse("2026-05-27T10:00:00Z"),
                "alice"
        ));

        assertThat(next).isEqualTo(previous);
        assertThat(repository.memberships).containsExactly(previous);
        assertThat(repository.closedMembershipId).isNull();
    }

    @Test
    void rejectsAssignmentInsideClosedHistoryForSameGroup() {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup group = repository.addGroup(type.id(), "risk-high", true);
        MerchantGroupMembership previous = repository.addMembership(
                "502118",
                group.id(),
                type.id(),
                Instant.parse("2026-05-26T09:00:00Z"),
                Instant.parse("2026-05-28T09:00:00Z")
        );

        assertThatThrownBy(() -> service.assignMembership(new AssignMembershipCommand(
                "502118",
                group.id(),
                Instant.parse("2026-05-27T10:00:00Z"),
                "alice"
        )))
                .isInstanceOf(MerchantGroupProblemException.class)
                .hasMessageContaining("INVALID_MEMBERSHIP_PERIOD");

        assertThat(repository.memberships).containsExactly(previous);
        assertThat(repository.closedMembershipId).isNull();
    }

    @Test
    void rejectsAssignmentInsideClosedHistoryForDifferentGroup() {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup low = repository.addGroup(type.id(), "risk-low", true);
        MerchantGroup high = repository.addGroup(type.id(), "risk-high", true);
        MerchantGroupMembership previous = repository.addMembership(
                "502118",
                low.id(),
                type.id(),
                Instant.parse("2026-05-26T09:00:00Z"),
                Instant.parse("2026-05-28T09:00:00Z")
        );

        assertThatThrownBy(() -> service.assignMembership(new AssignMembershipCommand(
                "502118",
                high.id(),
                Instant.parse("2026-05-27T10:00:00Z"),
                "alice"
        )))
                .isInstanceOf(MerchantGroupProblemException.class)
                .hasMessageContaining("INVALID_MEMBERSHIP_PERIOD");

        assertThat(repository.memberships).containsExactly(previous);
        assertThat(repository.closedMembershipId).isNull();
    }

    @Test
    void rejectsAssignmentWithoutValidFrom() {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup group = repository.addGroup(type.id(), "risk-high", true);

        assertThatThrownBy(() -> service.assignMembership(new AssignMembershipCommand(
                "502118",
                group.id(),
                null,
                "alice"
        )))
                .isInstanceOf(MerchantGroupProblemException.class)
                .hasMessageContaining("VALIDATION_ERROR");
    }

    @Test
    void rejectsSameInstantReplacementThatWouldCreateZeroLengthHistory() {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup low = repository.addGroup(type.id(), "risk-low", true);
        MerchantGroup high = repository.addGroup(type.id(), "risk-high", true);
        MerchantGroupMembership previous = repository.addMembership(
                "502118",
                low.id(),
                type.id(),
                Instant.parse("2026-05-27T10:00:00Z"),
                null
        );

        assertThatThrownBy(() -> service.assignMembership(new AssignMembershipCommand(
                "502118",
                high.id(),
                Instant.parse("2026-05-27T10:00:00Z"),
                "alice"
        )))
                .isInstanceOf(MerchantGroupProblemException.class)
                .hasMessageContaining("INVALID_MEMBERSHIP_PERIOD");

        assertThat(repository.memberships).containsExactly(previous);
        assertThat(repository.closedMembershipId).isNull();
    }

    @Test
    void rejectsAssignmentThatWouldOverlapFutureMembership() {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup low = repository.addGroup(type.id(), "risk-low", true);
        MerchantGroup high = repository.addGroup(type.id(), "risk-high", true);
        MerchantGroupMembership future = repository.addMembership(
                "502118",
                low.id(),
                type.id(),
                Instant.parse("2026-05-28T09:00:00Z"),
                null
        );

        assertThatThrownBy(() -> service.assignMembership(new AssignMembershipCommand(
                "502118",
                high.id(),
                Instant.parse("2026-05-27T10:00:00Z"),
                "alice"
        )))
                .isInstanceOf(MerchantGroupProblemException.class)
                .hasMessageContaining("INVALID_MEMBERSHIP_PERIOD");

        assertThat(repository.memberships).containsExactly(future);
        assertThat(repository.closedMembershipId).isNull();
    }

    @Test
    void allowsConcurrentMembershipsInDifferentTypes() {
        MerchantGroupType riskType = repository.addType("risk-tier", true);
        MerchantGroupType segmentType = repository.addType("segment", true);
        MerchantGroup risk = repository.addGroup(riskType.id(), "risk-high", true);
        MerchantGroup segment = repository.addGroup(segmentType.id(), "vip", true);

        service.assignMembership(new AssignMembershipCommand("502118", risk.id(), Instant.parse("2026-05-27T10:00:00Z"), "alice"));
        service.assignMembership(new AssignMembershipCommand("502118", segment.id(), Instant.parse("2026-05-27T10:00:00Z"), "alice"));

        assertThat(repository.memberships).hasSize(2);
    }

    @Test
    void rejectsAssignmentToDisabledGroup() {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup group = repository.addGroup(type.id(), "risk-high", false);

        assertThatThrownBy(() -> service.assignMembership(new AssignMembershipCommand(
                "502118",
                group.id(),
                Instant.parse("2026-05-27T10:00:00Z"),
                "alice"
        )))
                .isInstanceOf(MerchantGroupProblemException.class)
                .hasMessageContaining("GROUP_DISABLED");
    }

    @Test
    void rejectsAssignmentToDisabledType() {
        MerchantGroupType type = repository.addType("risk-tier", false);
        MerchantGroup group = repository.addGroup(type.id(), "risk-high", true);

        assertThatThrownBy(() -> service.assignMembership(new AssignMembershipCommand(
                "502118",
                group.id(),
                Instant.parse("2026-05-27T10:00:00Z"),
                "alice"
        )))
                .isInstanceOf(MerchantGroupProblemException.class)
                .hasMessageContaining("GROUP_TYPE_DISABLED");
    }

    static class FakeRepository implements MerchantGroupRepository {
        final List<MerchantGroupType> types = new ArrayList<>();
        final List<MerchantGroup> groups = new ArrayList<>();
        final List<MerchantGroupMembership> memberships = new ArrayList<>();
        UUID closedMembershipId;
        Instant closedValidTo;
        UUID replacedMembershipId;

        MerchantGroupType addType(String code, boolean enabled) {
            MerchantGroupType type = new MerchantGroupType(UUID.randomUUID(), code, code, null, enabled, 0, Instant.EPOCH, Instant.EPOCH);
            types.add(type);
            return type;
        }

        MerchantGroup addGroup(UUID typeId, String code, boolean enabled) {
            MerchantGroup group = new MerchantGroup(UUID.randomUUID(), typeId, code, code, null, enabled, Instant.EPOCH, Instant.EPOCH);
            groups.add(group);
            return group;
        }

        MerchantGroupMembership addMembership(String merchantId, UUID groupId, UUID groupTypeId, Instant validFrom, Instant validTo) {
            MerchantGroupMembership membership = new MerchantGroupMembership(
                    UUID.randomUUID(), merchantId, groupId, groupTypeId, validFrom, validTo, Instant.EPOCH, "test", null, null);
            memberships.add(membership);
            return membership;
        }

        @Override
        public List<MerchantGroupType> listTypes() {
            return List.copyOf(types);
        }

        @Override
        public MerchantGroupType saveType(MerchantGroupType type) {
            types.add(type);
            return type;
        }

        @Override
        public MerchantGroupType updateType(MerchantGroupType type) {
            types.replaceAll(existing -> existing.id().equals(type.id()) ? type : existing);
            return type;
        }

        @Override
        public Optional<MerchantGroupType> findType(UUID typeId) {
            return types.stream().filter(type -> type.id().equals(typeId)).findFirst();
        }

        @Override
        public List<MerchantGroup> listGroups(UUID typeId) {
            return groups.stream()
                    .filter(group -> typeId == null || group.typeId().equals(typeId))
                    .toList();
        }

        @Override
        public MerchantGroup saveGroup(MerchantGroup group) {
            groups.add(group);
            return group;
        }

        @Override
        public MerchantGroup updateGroup(MerchantGroup group) {
            groups.replaceAll(existing -> existing.id().equals(group.id()) ? group : existing);
            return group;
        }

        @Override
        public Optional<MerchantGroup> findGroup(UUID groupId) {
            return groups.stream().filter(group -> group.id().equals(groupId)).findFirst();
        }

        @Override
        public List<MerchantGroupMembership> listMemberships(String merchantId, UUID typeId, UUID groupId, String state, Instant at) {
            return memberships.stream()
                    .filter(membership -> merchantId == null || membership.merchantId().equals(merchantId))
                    .filter(membership -> typeId == null || membership.groupTypeId().equals(typeId))
                    .filter(membership -> groupId == null || membership.groupId().equals(groupId))
                    .toList();
        }

        @Override
        public Optional<MerchantGroupMembership> findMembership(UUID membershipId) {
            return memberships.stream().filter(membership -> membership.id().equals(membershipId)).findFirst();
        }

        @Override
        public Optional<MerchantGroupMembership> findActiveMembership(String merchantId, UUID groupTypeId, Instant at) {
            return memberships.stream()
                    .filter(membership -> membership.merchantId().equals(merchantId))
                    .filter(membership -> membership.groupTypeId().equals(groupTypeId))
                    .filter(membership -> !membership.validFrom().isAfter(at))
                    .filter(membership -> membership.validTo() == null || membership.validTo().isAfter(at))
                    .findFirst();
        }

        @Override
        public Optional<MerchantGroupMembership> findOverlappingMembership(String merchantId, UUID groupTypeId, Instant validFrom) {
            return memberships.stream()
                    .filter(membership -> membership.merchantId().equals(merchantId))
                    .filter(membership -> membership.groupTypeId().equals(groupTypeId))
                    .filter(membership -> membership.validTo() == null || membership.validTo().isAfter(validFrom))
                    .sorted((left, right) -> left.validFrom().compareTo(right.validFrom()))
                    .findFirst();
        }

        @Override
        public MerchantGroupMembership closeMembership(UUID membershipId, Instant validTo, Instant closedAt, String closedBy) {
            closedMembershipId = membershipId;
            closedValidTo = validTo;
            final MerchantGroupMembership[] closed = new MerchantGroupMembership[1];
            memberships.replaceAll(membership -> membership.id().equals(membershipId)
                    ? (closed[0] = membership.close(validTo, closedAt, closedBy))
                    : membership);
            return closed[0];
        }

        @Override
        public MerchantGroupMembership saveMembership(MerchantGroupMembership membership) {
            memberships.add(membership);
            return membership;
        }

        @Override
        public MerchantGroupMembership replaceMembership(
                UUID membershipId,
                Instant validTo,
                Instant closedAt,
                String closedBy,
                MerchantGroupMembership membership
        ) {
            replacedMembershipId = membershipId;
            closeMembership(membershipId, validTo, closedAt, closedBy);
            return saveMembership(membership);
        }
    }
}
