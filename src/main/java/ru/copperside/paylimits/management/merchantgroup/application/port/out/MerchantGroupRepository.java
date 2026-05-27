package ru.copperside.paylimits.management.merchantgroup.application.port.out;

import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroup;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupMembership;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupType;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MerchantGroupRepository {
    MerchantGroupType saveType(MerchantGroupType type);

    Optional<MerchantGroupType> findType(UUID typeId);

    MerchantGroup saveGroup(MerchantGroup group);

    Optional<MerchantGroup> findGroup(UUID groupId);

    Optional<MerchantGroupMembership> findActiveMembership(String merchantId, UUID groupTypeId, Instant at);

    Optional<MerchantGroupMembership> findOverlappingMembership(String merchantId, UUID groupTypeId, Instant validFrom);

    void closeMembership(UUID membershipId, Instant validTo, Instant closedAt, String closedBy);

    MerchantGroupMembership saveMembership(MerchantGroupMembership membership);

    MerchantGroupMembership replaceMembership(
            UUID membershipId,
            Instant validTo,
            Instant closedAt,
            String closedBy,
            MerchantGroupMembership membership
    );
}
