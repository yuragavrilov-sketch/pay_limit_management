package ru.copperside.paylimits.management.limitrule.adapter.in.web;

import ru.copperside.paylimits.management.limitrule.domain.LimitRule;

import java.util.UUID;

public record LimitRuleResponse(
        UUID id,
        String code,
        int version,
        String name,
        String direction,
        Selector operationSelector,
        Selector attributeSelector,
        String targetType,
        String metric,
        String period,
        String currency,
        String status,
        boolean enabled
) {
    public static LimitRuleResponse from(LimitRule rule) {
        return new LimitRuleResponse(
                rule.id(),
                rule.code(),
                rule.version(),
                rule.name(),
                rule.direction().name(),
                new Selector(rule.operationSelector().type().name(), rule.operationSelector().value()),
                new Selector(rule.attributeSelector().type().name(), rule.attributeSelector().value()),
                rule.targetType().name(),
                rule.metric().name(),
                rule.period().name(),
                rule.currency(),
                rule.status().name(),
                rule.active()
        );
    }

    public record Selector(String type, String value) {
    }
}
