package ru.copperside.paylimits.management.runtimeconfig.domain.wire;

import java.util.List;
import java.util.UUID;

/**
 * Rule in §4.3 flat form: no {@code matcher} wrapper. {@code operationTypes}, {@code direction} and
 * {@code limitTargetType} sit directly on the rule. {@code limitValue} is a decimal string (via
 * {@code BigDecimal#toPlainString}) or {@code null} for INTERVAL rules that carry no numeric limit.
 * {@code attributeSelector} is an extension beyond §4.3.
 */
public record RuleV2(
        UUID ruleId,
        String code,
        int version,
        MeasureV2 measure,
        String limitValue,
        List<String> operationTypes,
        String direction,
        String limitTargetType,
        String errorMessageTemplate,
        AttributeSelectorV2 attributeSelector
) {
    public RuleV2 {
        operationTypes = operationTypes == null ? List.of() : List.copyOf(operationTypes);
    }
}
