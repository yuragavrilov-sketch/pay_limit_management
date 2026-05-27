package ru.copperside.paylimits.management.limitrule.application;

import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;

import java.util.UUID;

public record PatchLimitRuleCommand(String name, UUID operationTypeId, RuleMetric metric, RulePeriod period) {
}
