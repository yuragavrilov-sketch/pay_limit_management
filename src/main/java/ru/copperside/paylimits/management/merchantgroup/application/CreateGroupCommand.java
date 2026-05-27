package ru.copperside.paylimits.management.merchantgroup.application;

import java.util.UUID;

public record CreateGroupCommand(UUID typeId, String code, String name, String description) {
}
