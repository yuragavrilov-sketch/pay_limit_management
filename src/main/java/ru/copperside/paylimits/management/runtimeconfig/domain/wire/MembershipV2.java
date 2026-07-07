package ru.copperside.paylimits.management.runtimeconfig.domain.wire;

import java.time.Instant;
import java.util.UUID;

/**
 * Membership in §4.3 form. {@code groupTypeId} is intentionally dropped from the wire — engine keys
 * memberships by {@code merchantId}/{@code groupId} only.
 */
public record MembershipV2(
        UUID membershipId,
        UUID groupId,
        String merchantId,
        Instant activeFrom,
        Instant activeTo
) {
}
