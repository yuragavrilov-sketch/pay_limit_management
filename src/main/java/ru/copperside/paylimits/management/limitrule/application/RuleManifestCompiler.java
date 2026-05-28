package ru.copperside.paylimits.management.limitrule.application;

import ru.copperside.paylimits.management.limitrule.application.port.out.RuleManifestRepository;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CompiledRule;
import ru.copperside.paylimits.management.limitrule.domain.DictionaryItem;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnosticSeverity;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifest;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestPayload;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestProblemException;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestStatus;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RuleManifestCompiler {

    private static final String DEFAULT_CURRENCY = "RUB";

    private final RuleManifestRepository repository;
    private final Clock clock;
    private final RuleManifestCanonicalJson canonicalJson;

    public RuleManifestCompiler(RuleManifestRepository repository, Clock clock) {
        this(repository, clock, new RuleManifestCanonicalJson());
    }

    RuleManifestCompiler(RuleManifestRepository repository, Clock clock, RuleManifestCanonicalJson canonicalJson) {
        this.repository = repository;
        this.clock = clock;
        this.canonicalJson = canonicalJson;
    }

    public RuleManifest compile() {
        RuleDictionaries dictionaries = repository.getRuleDictionaries();
        List<LimitRule> activeRules = repository.listActiveRulesForCompilation().stream()
                .filter(LimitRule::active)
                .sorted(Comparator.comparing(LimitRule::code)
                        .thenComparingInt(LimitRule::version)
                        .thenComparing(rule -> rule.id().toString()))
                .toList();

        List<ManifestDiagnostic> diagnostics = new ArrayList<>();
        List<CompiledRule> compiledRules = activeRules.stream()
                .map(this::compileRule)
                .toList();

        DictionaryIndex dictionaryIndex = new DictionaryIndex(dictionaries);
        validateRules(activeRules, compiledRules, dictionaryIndex, diagnostics);
        if (diagnostics.isEmpty()) {
            detectDuplicateRules(compiledRules, diagnostics);
            detectOperationScopeOverlaps(compiledRules, dictionaryIndex.operationTypesByCode(), diagnostics);
        }

        if (!diagnostics.isEmpty()) {
            throw new RuleManifestProblemException(
                    "RULE_MANIFEST_CONFLICT",
                    "Active rules cannot be compiled into a manifest",
                    diagnostics
            );
        }

        return repository.saveNextManifest(version -> buildManifest(version, compiledRules));
    }

    public RuleManifest getLatest() {
        return repository.findLatestManifest()
                .orElseThrow(() -> problemNotFound());
    }

    public RuleManifest getManifest(UUID id) {
        if (id == null) {
            throw problemNotFound();
        }
        return repository.findManifest(id)
                .orElseThrow(() -> problemNotFound());
    }

    private CompiledRule compileRule(LimitRule rule) {
        return new CompiledRule(
                rule.id(),
                rule.code(),
                rule.version(),
                new CompiledRule.Matcher(
                        rule.operationSelector(),
                        rule.direction(),
                        rule.attributeSelector(),
                        rule.targetType()
                ),
                new CompiledRule.Measure(
                        rule.metric(),
                        rule.period(),
                        rule.currency()
                )
        );
    }

    private void validateRules(
            List<LimitRule> rules,
            List<CompiledRule> compiledRules,
            DictionaryIndex dictionaryIndex,
            List<ManifestDiagnostic> diagnostics
    ) {
        for (int i = 0; i < rules.size(); i++) {
            LimitRule rule = rules.get(i);
            validateStructure(rule, i, diagnostics);
            validateOperationSelector(rule, i, dictionaryIndex, diagnostics);
            validateAttributeSelector(rule, i, dictionaryIndex, diagnostics);
            validateEnumMembership(rule, i, dictionaryIndex.dictionaries(), diagnostics);
        }
    }

    private void validateStructure(LimitRule rule, int index, List<ManifestDiagnostic> diagnostics) {
        if (rule.status() != RuleStatus.ACTIVE || rule.activatedAt() == null || rule.disabledAt() != null) {
            diagnostics.add(diagnostic(
                    "MANIFEST_INVALID_RULE_DEFINITION",
                    "Active rule lifecycle state is invalid",
                    List.of(rule.id()),
                    "rules[" + index + "].status"
            ));
        }
        if (rule.metric() == RuleMetric.AMOUNT && !DEFAULT_CURRENCY.equals(rule.currency())) {
            diagnostics.add(diagnostic(
                    "MANIFEST_INVALID_RULE_DEFINITION",
                    "AMOUNT rules must use RUB currency",
                    List.of(rule.id()),
                    "rules[" + index + "].measure.currency"
            ));
        }
        if (rule.metric() == RuleMetric.COUNT && rule.currency() != null) {
            diagnostics.add(diagnostic(
                    "MANIFEST_INVALID_RULE_DEFINITION",
                    "COUNT rules must not define currency",
                    List.of(rule.id()),
                    "rules[" + index + "].measure.currency"
            ));
        }
        validateSelectorShape(rule.id(), rule.operationSelector(), OperationSelectorType.ANY, index,
                "matcher.operation", diagnostics);
        validateSelectorShape(rule.id(), rule.attributeSelector(), AttributeSelectorType.NONE, index,
                "matcher.attribute", diagnostics);
    }

    private <T extends Enum<T>> void validateSelectorShape(
            UUID ruleId,
            RuleSelector<T> selector,
            T noValueType,
            int index,
            String path,
            List<ManifestDiagnostic> diagnostics
    ) {
        boolean invalid = selector == null
                || selector.type() == null
                || (selector.type() == noValueType && selector.value() != null)
                || (selector.type() != noValueType && (selector.value() == null || selector.value().isBlank()));
        if (invalid) {
            diagnostics.add(diagnostic(
                    "MANIFEST_INVALID_RULE_DEFINITION",
                    "Selector type and value are incompatible",
                    List.of(ruleId),
                    "rules[" + index + "]." + path
            ));
        }
    }

    private void validateOperationSelector(
            LimitRule rule,
            int index,
            DictionaryIndex dictionaryIndex,
            List<ManifestDiagnostic> diagnostics
    ) {
        RuleSelector<OperationSelectorType> selector = rule.operationSelector();
        if (selector == null || selector.type() == null || selector.type() == OperationSelectorType.ANY) {
            return;
        }

        if (selector.type() == OperationSelectorType.FAMILY) {
            DictionaryItem family = dictionaryIndex.operationFamiliesByCode().get(selector.value());
            validateDictionaryItem(rule.id(), family, "Operation family", index, "matcher.operation", diagnostics);
            return;
        }

        OperationType operationType = dictionaryIndex.operationTypesByCode().get(selector.value());
        if (operationType == null) {
            diagnostics.add(dictionaryMissing(rule.id(), "Operation type", index, "matcher.operation"));
            return;
        }
        if (!operationType.enabled()) {
            diagnostics.add(dictionaryDisabled(rule.id(), "Operation type", index, "matcher.operation"));
        }
        if (operationType.enabled()
                && operationType.direction() != OperationDirection.ALL
                && operationType.direction() != rule.direction()) {
            diagnostics.add(diagnostic(
                    "MANIFEST_DIRECTION_CONFLICT",
                    "Operation type direction is incompatible with rule direction",
                    List.of(rule.id()),
                    "rules[" + index + "].matcher.direction"
            ));
        }
    }

    private void validateAttributeSelector(
            LimitRule rule,
            int index,
            DictionaryIndex dictionaryIndex,
            List<ManifestDiagnostic> diagnostics
    ) {
        RuleSelector<AttributeSelectorType> selector = rule.attributeSelector();
        if (selector == null || selector.type() == null || selector.type() == AttributeSelectorType.NONE) {
            return;
        }
        DictionaryItem item = dictionaryIndex.attributeValues(selector.type()).get(selector.value());
        validateDictionaryItem(rule.id(), item, "Attribute value", index, "matcher.attribute", diagnostics);
    }

    private void validateDictionaryItem(
            UUID ruleId,
            DictionaryItem item,
            String label,
            int index,
            String path,
            List<ManifestDiagnostic> diagnostics
    ) {
        if (item == null) {
            diagnostics.add(dictionaryMissing(ruleId, label, index, path));
        } else if (!item.enabled()) {
            diagnostics.add(dictionaryDisabled(ruleId, label, index, path));
        }
    }

    private void validateEnumMembership(
            LimitRule rule,
            int index,
            RuleDictionaries dictionaries,
            List<ManifestDiagnostic> diagnostics
    ) {
        if (!dictionaries.directions().contains(rule.direction())
                || !dictionaries.targetTypes().contains(rule.targetType())
                || !dictionaries.metrics().contains(rule.metric())
                || !dictionaries.periods().contains(rule.period())
                || rule.operationSelector() == null
                || !dictionaries.operationSelectorTypes().contains(rule.operationSelector().type())
                || rule.attributeSelector() == null
                || !dictionaries.attributeSelectorTypes().contains(rule.attributeSelector().type())) {
            diagnostics.add(diagnostic(
                    "MANIFEST_INVALID_RULE_DEFINITION",
                    "Rule references unsupported enum value",
                    List.of(rule.id()),
                    "rules[" + index + "]"
            ));
        }
    }

    private void detectDuplicateRules(List<CompiledRule> rules, List<ManifestDiagnostic> diagnostics) {
        Map<MatcherMeasureKey, List<CompiledRule>> byMatcherMeasure = rules.stream()
                .collect(Collectors.groupingBy(
                        MatcherMeasureKey::new,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        byMatcherMeasure.values().stream()
                .filter(group -> group.size() > 1)
                .forEach(group -> diagnostics.add(diagnostic(
                        "MANIFEST_DUPLICATE_RULE",
                        "Two active rules compile to the same matcher and measure",
                        group.stream().map(CompiledRule::ruleId).toList(),
                        "rules"
                )));
    }

    private void detectOperationScopeOverlaps(
            List<CompiledRule> rules,
            Map<String, OperationType> operationTypesByCode,
            List<ManifestDiagnostic> diagnostics
    ) {
        Map<NonOperationKey, List<CompiledRule>> byNonOperation = rules.stream()
                .collect(Collectors.groupingBy(
                        NonOperationKey::new,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        for (List<CompiledRule> group : byNonOperation.values()) {
            for (int left = 0; left < group.size(); left++) {
                for (int right = left + 1; right < group.size(); right++) {
                    if (operationScopesOverlap(group.get(left), group.get(right), operationTypesByCode)) {
                        diagnostics.add(diagnostic(
                                "MANIFEST_OVERLAPPING_OPERATION_SCOPE",
                                "Operation scopes overlap for the same matcher and measure",
                                List.of(group.get(left).ruleId(), group.get(right).ruleId()),
                                "rules"
                        ));
                    }
                }
            }
        }
    }

    private boolean operationScopesOverlap(
            CompiledRule left,
            CompiledRule right,
            Map<String, OperationType> operationTypesByCode
    ) {
        RuleSelector<OperationSelectorType> leftSelector = left.matcher().operation();
        RuleSelector<OperationSelectorType> rightSelector = right.matcher().operation();
        if (leftSelector.type() == rightSelector.type()) {
            return false;
        }
        if (leftSelector.type() == OperationSelectorType.ANY || rightSelector.type() == OperationSelectorType.ANY) {
            return true;
        }
        RuleSelector<OperationSelectorType> familySelector = leftSelector.type() == OperationSelectorType.FAMILY
                ? leftSelector
                : rightSelector;
        RuleSelector<OperationSelectorType> typeSelector = leftSelector.type() == OperationSelectorType.TYPE
                ? leftSelector
                : rightSelector;
        OperationType operationType = operationTypesByCode.get(typeSelector.value());
        return operationType != null && familySelector.value().equals(operationType.familyCode());
    }

    private RuleManifest buildManifest(int version, List<CompiledRule> compiledRules) {
        Instant createdAt = Instant.now(clock);
        RuleManifestPayload payload = new RuleManifestPayload(
                version,
                RuleManifestStatus.VALID,
                compiledRules.size(),
                createdAt,
                compiledRules,
                List.of()
        );
        return new RuleManifest(
                UUID.randomUUID(),
                payload.version(),
                payload.status(),
                canonicalJson.checksum(payload),
                payload.ruleCount(),
                payload.createdAt(),
                payload.rules(),
                payload.diagnostics(),
                payload
        );
    }

    private ManifestDiagnostic dictionaryMissing(UUID ruleId, String label, int index, String path) {
        return diagnostic(
                "MANIFEST_DICTIONARY_VALUE_MISSING",
                label + " is missing",
                List.of(ruleId),
                "rules[" + index + "]." + path
        );
    }

    private ManifestDiagnostic dictionaryDisabled(UUID ruleId, String label, int index, String path) {
        return diagnostic(
                "MANIFEST_DICTIONARY_VALUE_DISABLED",
                label + " is disabled",
                List.of(ruleId),
                "rules[" + index + "]." + path
        );
    }

    private ManifestDiagnostic diagnostic(String code, String message, List<UUID> ruleIds, String path) {
        return new ManifestDiagnostic(code, ManifestDiagnosticSeverity.ERROR, message, ruleIds, path);
    }

    private RuleManifestProblemException problemNotFound() {
        return new RuleManifestProblemException("RULE_MANIFEST_NOT_FOUND", "Rule manifest not found");
    }

    private record MatcherMeasureKey(CompiledRule rule) {
        private MatcherMeasureKey {
            rule = new CompiledRule(
                    null,
                    null,
                    0,
                    rule.matcher(),
                    rule.measure()
            );
        }
    }

    private record NonOperationKey(
            OperationDirection direction,
            RuleSelector<AttributeSelectorType> attribute,
            LimitTargetType targetType,
            CompiledRule.Measure measure
    ) {
        NonOperationKey(CompiledRule rule) {
            this(rule.matcher().direction(), rule.matcher().attribute(), rule.matcher().targetType(), rule.measure());
        }
    }

    private record DictionaryIndex(
            RuleDictionaries dictionaries,
            Map<String, DictionaryItem> operationFamiliesByCode,
            Map<String, OperationType> operationTypesByCode,
            Map<String, DictionaryItem> paymentSystemsByCode,
            Map<String, DictionaryItem> issuerCountriesByCode,
            Map<String, DictionaryItem> issuerBanksByCode,
            Map<String, DictionaryItem> binsByCode,
            Map<String, DictionaryItem> cardTypesByCode,
            Map<String, DictionaryItem> cardLevelsByCode
    ) {
        DictionaryIndex(RuleDictionaries dictionaries) {
            this(
                    dictionaries,
                    byCode(dictionaries.operationFamilies(), DictionaryItem::code),
                    byCode(dictionaries.operationTypes(), OperationType::code),
                    byCode(dictionaries.paymentSystems(), DictionaryItem::code),
                    byCode(dictionaries.issuerCountries(), DictionaryItem::code),
                    byCode(dictionaries.issuerBanks(), DictionaryItem::code),
                    byCode(dictionaries.bins(), DictionaryItem::code),
                    byCode(dictionaries.cardTypes(), DictionaryItem::code),
                    byCode(dictionaries.cardLevels(), DictionaryItem::code)
            );
        }

        Map<String, DictionaryItem> attributeValues(AttributeSelectorType type) {
            return switch (type) {
                case PAYMENT_SYSTEM -> paymentSystemsByCode;
                case ISSUER_COUNTRY -> issuerCountriesByCode;
                case BANK -> issuerBanksByCode;
                case BIN -> binsByCode;
                case CARD_TYPE -> cardTypesByCode;
                case CARD_LEVEL -> cardLevelsByCode;
                case NONE -> Map.of();
            };
        }

        private static <T> Map<String, T> byCode(List<T> values, Function<T, String> code) {
            return values.stream().collect(Collectors.toMap(
                    code,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new
            ));
        }
    }
}
