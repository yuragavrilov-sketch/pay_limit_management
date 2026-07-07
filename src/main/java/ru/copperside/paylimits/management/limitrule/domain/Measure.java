package ru.copperside.paylimits.management.limitrule.domain;

public record Measure(
        RuleMetric metric,
        RulePeriod period,
        AggregationScope aggregationScope,
        String currency,
        Integer intervalMinutes
) {
}
