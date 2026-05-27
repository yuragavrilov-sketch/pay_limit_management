package ru.copperside.paylimits.management.merchantgroup.application.port.out;

import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroup;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupMembership;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantGroupRepository {
    List<MerchantGroupType> listTypes();

    MerchantGroupType saveType(MerchantGroupType type);

    MerchantGroupType updateType(MerchantGroupType type);

    Optional<MerchantGroupType> findType(UUID typeId);

    List<MerchantGroup> listGroups(UUID typeId);

    MerchantGroup saveGroup(MerchantGroup group);

    MerchantGroup updateGroup(MerchantGroup group);

    Optional<MerchantGroup> findGroup(UUID groupId);

    List<MerchantGroupMembership> listMemberships(String merchantId, UUID typeId, UUID groupId, String state, Instant at);

    Optional<MerchantGroupMembership> findMembership(UUID membershipId);

    Optional<MerchantGroupMembership> findActiveMembership(String merchantId, UUID groupTypeId, Instant at);

    Optional<MerchantGroupMembership> findOverlappingMembership(String merchantId, UUID groupTypeId, Instant validFrom);

    MerchantGroupMembership closeMembership(UUID membershipId, Instant validTo, Instant closedAt, String closedBy);

    MerchantGroupMembership saveMembership(MerchantGroupMembership membership);

    MerchantGroupMembership replaceMembership(
            UUID membershipId,
            Instant validTo,
            Instant closedAt,
            String closedBy,
            MerchantGroupMembership membership
    );
}
