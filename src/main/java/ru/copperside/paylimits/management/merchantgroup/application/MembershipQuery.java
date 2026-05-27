package ru.copperside.paylimits.management.merchantgroup.application;

import java.util.UUID;

public record MembershipQuery(String merchantId, UUID typeId, UUID groupId, String state) {
}
