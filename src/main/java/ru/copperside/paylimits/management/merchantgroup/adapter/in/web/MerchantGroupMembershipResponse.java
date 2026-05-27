package ru.copperside.paylimits.management.merchantgroup.adapter.in.web;

import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupMembership;

import java.time.Instant;
import java.util.UUID;

public record MerchantGroupMembershipResponse(
        UUID id,
        String merchantId,
        UUID groupId,
        UUID groupTypeId,
        Instant validFrom,
        Instant validTo,
        Instant createdAt,
        String createdBy,
        Instant closedAt,
        String closedBy
) {
    static MerchantGroupMembershipResponse from(MerchantGroupMembership membership) {
        return new MerchantGroupMembershipResponse(
                membership.id(),
                membership.merchantId(),
                membership.groupId(),
                membership.groupTypeId(),
                membership.validFrom(),
                membership.validTo(),
                membership.createdAt(),
                membership.createdBy(),
                membership.closedAt(),
                membership.closedBy()
        );
    }
}
