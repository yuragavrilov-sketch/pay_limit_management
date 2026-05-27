package ru.copperside.paylimits.management.merchantgroup.application;

import java.time.Instant;
import java.util.UUID;

public record CloseMembershipCommand(UUID membershipId, Instant validTo, String actor) {
}
