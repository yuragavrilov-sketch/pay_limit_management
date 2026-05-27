package ru.copperside.paylimits.management.merchantgroup.domain;

import java.time.Instant;
import java.util.UUID;

public record MerchantGroupMembership(
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
    public MerchantGroupMembership close(Instant validTo, Instant closedAt, String closedBy) {
        return new MerchantGroupMembership(id, merchantId, groupId, groupTypeId, validFrom, validTo, createdAt, createdBy, closedAt, closedBy);
    }
}
