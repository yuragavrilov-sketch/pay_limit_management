package ru.copperside.paylimits.management.limitrule.application;

import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;

public record CreateOperationTypeCommand(
        String code,
        String name,
        String familyCode,
        OperationDirection direction,
        CounterpartyType counterpartyType
) {
}
