package ru.copperside.paylimits.management.runtimeconfig.domain;

import java.time.Instant;
import java.util.UUID;

public record RuntimeMerchantGroupMembership(
        UUID membershipId,
        String merchantId,
        UUID groupTypeId,
        UUID groupId,
        Instant validFrom,
        Instant validTo
) {
}
