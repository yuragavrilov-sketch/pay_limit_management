package ru.copperside.paylimits.management.merchantgroup.application;

import java.time.Instant;
import java.util.UUID;

public record AssignMembershipCommand(String merchantId, UUID groupId, Instant validFrom, String actor) {
}
