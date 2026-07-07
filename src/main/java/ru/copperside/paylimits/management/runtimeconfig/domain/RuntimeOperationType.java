package ru.copperside.paylimits.management.runtimeconfig.domain;

import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;

public record RuntimeOperationType(
        String code,
        OperationDirection direction,
        CounterpartyType counterpartyType
) {
}
