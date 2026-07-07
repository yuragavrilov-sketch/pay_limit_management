package ru.copperside.paylimits.management.limitrule.domain;

import java.util.List;

public record RuleDictionaries(
        List<DictionaryItem> operationFamilies,
        List<OperationType> operationTypes,
        List<DictionaryItem> paymentSystems,
        List<DictionaryItem> issuerCountries,
        List<DictionaryItem> issuerBanks,
        List<DictionaryItem> bins,
        List<DictionaryItem> cardTypes,
        List<DictionaryItem> cardLevels,
        List<OperationDirection> directions,
        List<AttributeSelectorType> attributeSelectorTypes,
        List<LimitTargetType> targetTypes,
        List<RuleMetric> metrics,
        List<RulePeriod> periods,
        List<CounterpartyType> counterpartyTypes,
        List<AggregationScope> aggregationScopes
) {
}
