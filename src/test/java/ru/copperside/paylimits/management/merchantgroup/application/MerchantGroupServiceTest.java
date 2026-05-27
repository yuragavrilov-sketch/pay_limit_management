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
        service = new MerchantGroupService(repository, CLOCK);
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

    static class FakeRepository implements MerchantGroupRepository {
        final List<MerchantGroupType> types = new ArrayList<>();
        final List<MerchantGroup> groups = new ArrayList<>();
        final List<MerchantGroupMembership> memberships = new ArrayList<>();
        UUID closedMembershipId;
        Instant closedValidTo;

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
        public MerchantGroupType saveType(MerchantGroupType type) {
            types.add(type);
            return type;
        }

        @Override
        public Optional<MerchantGroupType> findType(UUID typeId) {
            return types.stream().filter(type -> type.id().equals(typeId)).findFirst();
        }

        @Override
        public MerchantGroup saveGroup(MerchantGroup group) {
            groups.add(group);
            return group;
        }

        @Override
        public Optional<MerchantGroup> findGroup(UUID groupId) {
            return groups.stream().filter(group -> group.id().equals(groupId)).findFirst();
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
        public void closeMembership(UUID membershipId, Instant validTo, Instant closedAt, String closedBy) {
            closedMembershipId = membershipId;
            closedValidTo = validTo;
            memberships.replaceAll(membership -> membership.id().equals(membershipId)
                    ? membership.close(validTo, closedAt, closedBy)
                    : membership);
        }

        @Override
        public MerchantGroupMembership saveMembership(MerchantGroupMembership membership) {
            memberships.add(membership);
            return membership;
        }
    }
}
