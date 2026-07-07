package ru.copperside.paylimits.management.limitrule.adapter.in.web;

import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.Measure;

import java.util.List;
import java.util.UUID;

public record LimitRuleResponse(
        UUID id,
        String code,
        int version,
        String name,
        List<String> operationTypes,
        String direction,
        MeasureView measure,
        String limitTargetType,
        String limitValue,
        String errorMessageTemplate,
        Selector attributeSelector,
        String status,
        boolean enabled
) {
    public static LimitRuleResponse from(LimitRule rule) {
        Measure m = rule.measure();
        return new LimitRuleResponse(
                rule.id(),
                rule.code(),
                rule.version(),
                rule.name(),
                List.copyOf(rule.operationTypes()),
                rule.direction().name(),
                new MeasureView(
                        m.metric().name(),
                        m.period() == null ? null : m.period().name(),
                        m.aggregationScope() == null ? null : m.aggregationScope().name(),
                        m.currency(),
                        m.intervalMinutes()),
                rule.limitTargetType() == null ? null : rule.limitTargetType().name(),
                rule.limitValue() == null ? null : rule.limitValue().toPlainString(),
                rule.errorMessageTemplate(),
                new Selector(rule.attributeSelector().type().name(), rule.attributeSelector().value()),
                rule.status().name(),
                rule.active()
        );
    }

    public record MeasureView(String metric, String period, String aggregationScope,
                              String currency, Integer intervalMinutes) {
    }

    public record Selector(String type, String value) {
    }
}
