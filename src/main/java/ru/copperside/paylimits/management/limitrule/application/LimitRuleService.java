package ru.copperside.paylimits.management.limitrule.application;

import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;
import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class LimitRuleService {

    private final LimitRuleRepository repository;
    private final LimitKindInvariantChecker invariantChecker;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    public LimitRuleService(
            LimitRuleRepository repository,
            LimitKindInvariantChecker invariantChecker,
            TransactionRunner transactionRunner,
            Clock clock
    ) {
        this.repository = repository;
        this.invariantChecker = invariantChecker;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
    }

    public RuleDictionaries getRuleDictionaries() {
        return repository.getRuleDictionaries();
    }

    public List<OperationType> listOperationTypes() {
        return repository.listOperationTypes();
    }

    public OperationType createOperationType(CreateOperationTypeCommand command) {
        requireCommand(command);
        Instant now = Instant.now(clock);
        return repository.saveOperationType(new OperationType(
                UUID.randomUUID(),
                requireText(command.code(), "code"),
                requireText(command.name(), "name"),
                requireText(command.familyCode(), "familyCode"),
                requireEnum(command.direction(), "direction"),
                requireEnum(command.counterpartyType(), "counterpartyType"),
                true,
                0,
                now,
                now
        ));
    }

    public OperationType patchOperationType(UUID id, PatchOperationTypeCommand command) {
        requireCommand(command);
        OperationType existing = repository.findOperationType(requireUuid(id, "operationTypeId"))
                .orElseThrow(() -> problem("OPERATION_TYPE_NOT_FOUND", "Operation type not found"));
        boolean enabled = command.enabled() == null ? existing.enabled() : command.enabled();
        if (!enabled && existing.enabled() && repository.hasActiveRulesForOperationTypeCode(existing.code())) {
            throw problem("OPERATION_TYPE_IN_USE", "Operation type is used by active rules");
        }
        OperationType updated = new OperationType(
                existing.id(),
                existing.code(),
                command.name() == null ? existing.name() : requireText(command.name(), "name"),
                command.familyCode() == null ? existing.familyCode() : requireText(command.familyCode(), "familyCode"),
                command.direction() == null ? existing.direction() : command.direction(),
                command.counterpartyType() == null ? existing.counterpartyType() : command.counterpartyType(),
                enabled,
                existing.sortOrder(),
                existing.createdAt(),
                Instant.now(clock)
        );
        return repository.updateOperationType(updated);
    }

    public List<LimitRule> listRules() {
        return repository.listRules();
    }

    public LimitRule getRule(UUID id) {
        return repository.findRule(requireUuid(id, "ruleId"))
                .orElseThrow(() -> problem("RULE_NOT_FOUND", "Rule not found"));
    }

    public LimitRule createRule(CreateLimitRuleCommand command) {
        requireCommand(command);
        String code = requireText(command.code(), "code");
        String name = requireText(command.name(), "name");
        OperationDirection direction = requireEnum(command.direction(), "direction");
        ValidatedRuleDefinition validated = validateRuleDefinition(
                command.operationTypes(), direction, command.measure(),
                command.limitTargetType(), command.limitValue(), command.errorMessageTemplate());
        Instant now = Instant.now(clock);
        LimitRule rule = new LimitRule(
                UUID.randomUUID(),
                code,
                repository.nextVersion(code),
                name,
                validated.operationTypes(),
                direction,
                validated.measure(),
                command.limitTargetType(),
                command.limitValue(),
                validated.errorMessageTemplate(),
                validateAttributeSelector(command.attributeSelector()),
                RuleStatus.DRAFT,
                now,
                now,
                null,
                null
        );
        rejectExistingDraft(code);
        return repository.saveRule(rule);
    }

    public LimitRule patchRule(UUID id, PatchLimitRuleCommand command) {
        requireCommand(command);
        LimitRule existing = getRule(id);
        requireDraft(existing);
        String name = command.name() == null ? existing.name() : requireText(command.name(), "name");
        Set<String> operationTypes = command.operationTypes() == null
                ? existing.operationTypes() : command.operationTypes();
        OperationDirection direction = command.direction() == null ? existing.direction() : command.direction();
        Measure measure = command.measure() == null ? existing.measure() : command.measure();
        LimitTargetType targetType = command.limitTargetType() == null
                ? existing.limitTargetType() : command.limitTargetType();
        BigDecimal limitValue = command.limitValue() == null ? existing.limitValue() : command.limitValue();
        String template = command.errorMessageTemplate() == null
                ? existing.errorMessageTemplate() : command.errorMessageTemplate();
        RuleSelector<AttributeSelectorType> attributeSelector = command.attributeSelector() == null
                ? existing.attributeSelector()
                : validateAttributeSelector(command.attributeSelector());

        ValidatedRuleDefinition validated = validateRuleDefinition(
                operationTypes, direction, measure, targetType, limitValue, template);

        LimitRule updated = new LimitRule(
                existing.id(),
                existing.code(),
                existing.version(),
                name,
                validated.operationTypes(),
                direction,
                validated.measure(),
                targetType,
                limitValue,
                validated.errorMessageTemplate(),
                attributeSelector,
                existing.status(),
                existing.createdAt(),
                Instant.now(clock),
                existing.activatedAt(),
                existing.disabledAt()
        );
        return repository.updateRule(updated);
    }

    public LimitRule activateRule(UUID id) {
        LimitRule existing = getRule(id);
        requireDraft(existing);
        repository.findActiveByCode(existing.code())
                .ifPresent(rule -> {
                    throw problem("RULE_STATUS_CONFLICT", "Another active rule already exists");
                });
        // Closes a review finding: a DRAFT may reference an operation type that was disabled (or
        // otherwise changed) after the draft was created. Re-resolve at activation time instead of
        // deferring the discovery to manifest compilation.
        resolveOperationTypes(existing.operationTypes(), existing.direction());
        Instant now = Instant.now(clock);
        LimitRule updated = new LimitRule(
                existing.id(),
                existing.code(),
                existing.version(),
                existing.name(),
                existing.operationTypes(),
                existing.direction(),
                existing.measure(),
                existing.limitTargetType(),
                existing.limitValue(),
                existing.errorMessageTemplate(),
                existing.attributeSelector(),
                RuleStatus.ACTIVE,
                existing.createdAt(),
                now,
                now,
                existing.disabledAt()
        );
        // Lock (by rule), the non-overlap invariant check across the rule's group assignments, and
        // the status write share a single transaction so the advisory lock actually serializes
        // concurrent assignment/activation changes for the same rule.
        return transactionRunner.run(() -> {
            invariantChecker.checkRuleActivation(existing.id(), now);
            return repository.updateRule(updated);
        });
    }

    public LimitRule disableRule(UUID id) {
        LimitRule existing = getRule(id);
        if (existing.status() != RuleStatus.ACTIVE) {
            throw problem("RULE_STATUS_CONFLICT", "Only active rules can be disabled");
        }
        Instant now = Instant.now(clock);
        LimitRule updated = new LimitRule(
                existing.id(),
                existing.code(),
                existing.version(),
                existing.name(),
                existing.operationTypes(),
                existing.direction(),
                existing.measure(),
                existing.limitTargetType(),
                existing.limitValue(),
                existing.errorMessageTemplate(),
                existing.attributeSelector(),
                RuleStatus.DISABLED,
                existing.createdAt(),
                now,
                existing.activatedAt(),
                now
        );
        return repository.updateRule(updated);
    }

    public LimitRule createNewVersion(UUID id) {
        LimitRule existing = getRule(id);
        if (existing.status() == RuleStatus.DRAFT) {
            throw problem("RULE_STATUS_CONFLICT", "Draft rules cannot be versioned");
        }
        rejectExistingDraft(existing.code());
        Instant now = Instant.now(clock);
        return repository.saveRule(new LimitRule(
                UUID.randomUUID(),
                existing.code(),
                repository.nextVersion(existing.code()),
                existing.name(),
                existing.operationTypes(),
                existing.direction(),
                existing.measure(),
                existing.limitTargetType(),
                existing.limitValue(),
                existing.errorMessageTemplate(),
                existing.attributeSelector(),
                RuleStatus.DRAFT,
                now,
                now,
                null,
                null
        ));
    }

    /**
     * Full rule model validation (spec §2.1, validations 1-4) plus the errorMessageTemplate
     * placeholder check. Resolves and re-validates operationTypes as a side effect, so callers
     * must not duplicate that resolution.
     */
    private ValidatedRuleDefinition validateRuleDefinition(
            Set<String> operationTypeCodes,
            OperationDirection direction,
            Measure measure,
            LimitTargetType targetType,
            BigDecimal limitValue,
            String errorMessageTemplate) {
        if (operationTypeCodes == null || operationTypeCodes.isEmpty()) {
            throw problem("VALIDATION_ERROR", "operationTypes must contain at least one code");
        }
        requireEnum(direction, "direction");
        if (measure == null) {
            throw problem("VALIDATION_ERROR", "measure must not be null");
        }
        RuleMetric metric = requireEnum(measure.metric(), "measure.metric");
        RulePeriod period = measure.period();
        AggregationScope scope = measure.aggregationScope();

        List<OperationType> resolved = resolveOperationTypes(operationTypeCodes, direction);

        // Validation 1: PER_OPERATION => metric=AMOUNT, no aggregationScope, no limitTargetType.
        if (period == RulePeriod.PER_OPERATION) {
            if (metric != RuleMetric.AMOUNT) {
                throw problem("VALIDATION_ERROR", "PER_OPERATION requires metric=AMOUNT");
            }
            if (scope != null) {
                throw problem("VALIDATION_ERROR", "PER_OPERATION must not define aggregationScope");
            }
            if (targetType != null) {
                throw problem("VALIDATION_ERROR", "PER_OPERATION must not define limitTargetType");
            }
            if (limitValue == null) {
                throw problem("VALIDATION_ERROR", "limitValue is required for PER_OPERATION rules");
            }
        } else if (metric == RuleMetric.INTERVAL) {
            // Validation 2: INTERVAL => aggregationScope=TARGET, intervalMinutes > 0, no period/limitValue.
            if (scope != AggregationScope.TARGET) {
                throw problem("VALIDATION_ERROR", "INTERVAL requires aggregationScope=TARGET");
            }
            if (measure.intervalMinutes() == null || measure.intervalMinutes() <= 0) {
                throw problem("VALIDATION_ERROR", "INTERVAL requires intervalMinutes > 0");
            }
            if (period != null || limitValue != null) {
                throw problem("VALIDATION_ERROR", "INTERVAL must not define period or limitValue");
            }
        } else {
            // AMOUNT/COUNT with a periodic (non-PER_OPERATION) window need aggregationScope and limitValue.
            requireEnum(scope, "measure.aggregationScope");
            if (limitValue == null) {
                throw problem("VALIDATION_ERROR", "limitValue is required for AMOUNT/COUNT rules");
            }
            if (period == null) {
                throw problem("VALIDATION_ERROR", "period is required for AMOUNT/COUNT rules");
            }
        }

        // Validation 4: TARGET scope requires a single counterparty type matching limitTargetType;
        // any other scope (OWNER; PER_OPERATION is already rejected by validation 1) must not
        // carry a limitTargetType at all.
        if (scope == AggregationScope.TARGET) {
            if (targetType == null) {
                throw problem("VALIDATION_ERROR", "TARGET scope requires limitTargetType");
            }
            Set<CounterpartyType> counterparties = resolved.stream()
                    .map(OperationType::counterpartyType)
                    .collect(Collectors.toSet());
            if (counterparties.size() != 1 || !counterpartyMatchesTarget(counterparties.iterator().next(), targetType)) {
                throw problem("VALIDATION_ERROR",
                        "TARGET rule operationTypes must share a single counterparty equal to limitTargetType");
            }
        } else if (targetType != null) {
            throw problem("VALIDATION_ERROR", "limitTargetType must be null unless aggregationScope=TARGET");
        }

        // Currency: AMOUNT rules require RUB (normalized on save); other metrics must not define one.
        String normalizedCurrency = null;
        if (metric == RuleMetric.AMOUNT) {
            if (measure.currency() == null || !"RUB".equals(measure.currency().trim().toUpperCase(Locale.ROOT))) {
                throw problem("VALIDATION_ERROR", "AMOUNT rules require currency=RUB");
            }
            normalizedCurrency = measure.currency().trim().toUpperCase(Locale.ROOT);
        } else if (measure.currency() != null) {
            throw problem("VALIDATION_ERROR", "currency is only allowed for AMOUNT rules");
        }

        String normalizedTemplate = validateErrorTemplate(errorMessageTemplate);

        Set<String> normalizedOperationTypes = resolved.stream()
                .map(OperationType::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Measure normalizedMeasure = new Measure(metric, period, scope, normalizedCurrency, measure.intervalMinutes());

        return new ValidatedRuleDefinition(normalizedOperationTypes, normalizedMeasure, normalizedTemplate);
    }

    /**
     * Explicit, exhaustive mapping between {@link CounterpartyType} and {@link LimitTargetType}
     * for validation 4 (TARGET scope). A {@code switch} over both enums makes a future divergence
     * between the two types a compile error rather than a silent runtime mismatch.
     */
    private boolean counterpartyMatchesTarget(CounterpartyType counterpartyType, LimitTargetType targetType) {
        return switch (counterpartyType) {
            case CARD -> targetType == LimitTargetType.CARD;
            case PHONE -> targetType == LimitTargetType.PHONE;
            case ACCOUNT -> targetType == LimitTargetType.ACCOUNT;
        };
    }

    /**
     * Resolves operation type codes and checks each is known, enabled, and matches the rule
     * direction (validation 3). Also reused by {@link #activateRule} to re-check a draft's
     * operation types are still valid at activation time.
     */
    private List<OperationType> resolveOperationTypes(Set<String> operationTypeCodes, OperationDirection direction) {
        List<OperationType> resolved = new ArrayList<>();
        for (String code : operationTypeCodes) {
            String value = requireText(code, "operationTypes");
            OperationType type = repository.findOperationTypeByCode(value)
                    .orElseThrow(() -> problem("RULE_SELECTOR_INVALID", "Operation type is not available: " + value));
            if (!type.enabled()) {
                throw problem("OPERATION_TYPE_DISABLED", "Operation type is disabled: " + type.code());
            }
            if (type.direction() != direction) {
                throw problem("VALIDATION_ERROR",
                        "operationType " + type.code() + " does not match rule direction");
            }
            resolved.add(type);
        }
        return resolved;
    }

    private static final Set<String> SUPPORTED_TEMPLATE_PLACEHOLDERS = Set.of("d", "f", "s", "%");

    private String validateErrorTemplate(String template) {
        String normalized = requireText(template, "errorMessageTemplate");
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '%') {
                if (i + 1 >= normalized.length()) {
                    throw problem("VALIDATION_ERROR", "errorMessageTemplate ends with a dangling '%'");
                }
                String token = String.valueOf(normalized.charAt(i + 1));
                if (!SUPPORTED_TEMPLATE_PLACEHOLDERS.contains(token)) {
                    throw problem("VALIDATION_ERROR", "errorMessageTemplate contains unsupported placeholder %" + token);
                }
                i++; // consume the placeholder char (handles %% correctly)
            }
        }
        return normalized;
    }

    private record ValidatedRuleDefinition(Set<String> operationTypes, Measure measure, String errorMessageTemplate) {
    }

    private RuleSelector<AttributeSelectorType> validateAttributeSelector(RuleSelector<AttributeSelectorType> selector) {
        if (selector == null || selector.type() == null) {
            return new RuleSelector<>(AttributeSelectorType.NONE, null);
        }
        if (selector.type() == AttributeSelectorType.NONE) {
            requireNoSelectorValue(selector, "attributeSelector");
            return new RuleSelector<>(AttributeSelectorType.NONE, null);
        }
        String value = requireText(selector.value(), "attributeSelector.value");
        if (!repository.attributeValueExists(selector.type(), value)) {
            throw problem("RULE_SELECTOR_INVALID", "Attribute selector value is not available");
        }
        return new RuleSelector<>(selector.type(), value);
    }

    private <T extends Enum<T>> void requireNoSelectorValue(RuleSelector<T> selector, String field) {
        if (selector.value() != null && !selector.value().isBlank()) {
            throw problem("VALIDATION_ERROR", field + ".value must be null");
        }
    }

    private void requireDraft(LimitRule rule) {
        if (rule.status() != RuleStatus.DRAFT) {
            throw problem("RULE_STATUS_CONFLICT", "Only draft rules can be edited");
        }
    }

    private void rejectExistingDraft(String code) {
        repository.findDraftByCode(code)
                .ifPresent(rule -> {
                    throw problem("RULE_DRAFT_EXISTS", "Draft rule already exists");
                });
    }

    private void requireCommand(Object command) {
        if (command == null) {
            throw problem("VALIDATION_ERROR", "command must not be null");
        }
    }

    private UUID requireUuid(UUID value, String field) {
        if (value == null) {
            throw problem("VALIDATION_ERROR", field + " must not be null");
        }
        return value;
    }

    private <T> T requireEnum(T value, String field) {
        if (value == null) {
            throw problem("VALIDATION_ERROR", field + " must not be null");
        }
        return value;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw problem("VALIDATION_ERROR", field + " must not be blank");
        }
        return value.trim();
    }

    private LimitRuleProblemException problem(String code, String message) {
        return new LimitRuleProblemException(code, message);
    }
}
