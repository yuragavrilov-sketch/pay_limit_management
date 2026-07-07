package ru.copperside.paylimits.management.runtimeconfig.domain.wire;

public record OperationTypeV2(
        String code,
        String direction,
        String counterpartyType
) {
}
