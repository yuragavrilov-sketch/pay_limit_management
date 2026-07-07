package ru.copperside.paylimits.management.runtimeconfig.domain.wire;

public record MeasureV2(
        String metric,
        String period,
        String aggregationScope,
        String currency,
        Integer intervalMinutes
) {
}
