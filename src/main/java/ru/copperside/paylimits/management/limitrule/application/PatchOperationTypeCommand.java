package ru.copperside.paylimits.management.limitrule.application;

import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;

public record PatchOperationTypeCommand(String name, String familyCode, OperationDirection direction, Boolean enabled) {
}
