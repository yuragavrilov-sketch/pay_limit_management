package ru.copperside.paylimits.management.common.invariant;

import ru.copperside.paylimits.management.limitrule.domain.LimitKind;

import java.util.List;

/**
 * Machine-readable view of a {@link LimitKind} used in the {@code conflicts} block of
 * limit-kind-invariant error responses (spec §3.4).
 */
public record LimitKindView(
        String checkType,
        String targetType,
        String direction,
        List<String> operationTypes
) {

    public static LimitKindView of(LimitKind limitKind) {
        String checkType = limitKind.period() == null
                ? limitKind.metric().name()
                : limitKind.metric().name() + "_" + limitKind.period().name();
        String targetType = limitKind.limitTargetType() == null ? null : limitKind.limitTargetType().name();
        String direction = limitKind.direction() == null ? null : limitKind.direction().name();
        List<String> operationTypes = limitKind.operationTypes().stream().sorted().toList();
        return new LimitKindView(checkType, targetType, direction, operationTypes);
    }
}
