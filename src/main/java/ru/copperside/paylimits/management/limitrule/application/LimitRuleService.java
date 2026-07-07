package ru.copperside.paylimits.management.limitrule.application;

import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class LimitRuleService {

    private final LimitRuleRepository repository;
    private final Clock clock;

    public LimitRuleService(LimitRuleRepository repository, Clock clock) {
        this.repository = repository;
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
        Instant now = Instant.now(clock);
        LimitRule rule = new LimitRule(
                UUID.randomUUID(),
                code,
                repository.nextVersion(code),
                requireText(command.name(), "name"),
                validateOperationTypes(command.operationTypes()),
                requireEnum(command.direction(), "direction"),
                requireMeasure(command.measure()),
                command.limitTargetType(),
                command.limitValue(),
                requireText(command.errorMessageTemplate(), "errorMessageTemplate"),
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
        LimitRule updated = new LimitRule(
                existing.id(),
                existing.code(),
                existing.version(),
                command.name() == null ? existing.name() : requireText(command.name(), "name"),
                command.operationTypes() == null
                        ? existing.operationTypes()
                        : validateOperationTypes(command.operationTypes()),
                command.direction() == null ? existing.direction() : command.direction(),
                command.measure() == null ? existing.measure() : requireMeasure(command.measure()),
                command.limitTargetType() == null ? existing.limitTargetType() : command.limitTargetType(),
                command.limitValue() == null ? existing.limitValue() : command.limitValue(),
                command.errorMessageTemplate() == null
                        ? existing.errorMessageTemplate()
                        : requireText(command.errorMessageTemplate(), "errorMessageTemplate"),
                command.attributeSelector() == null
                        ? existing.attributeSelector()
                        : validateAttributeSelector(command.attributeSelector()),
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
        return repository.updateRule(updated);
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

    private Set<String> validateOperationTypes(Set<String> operationTypes) {
        if (operationTypes == null || operationTypes.isEmpty()) {
            throw problem("VALIDATION_ERROR", "operationTypes must not be empty");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String code : operationTypes) {
            String value = requireText(code, "operationTypes");
            OperationType type = repository.findOperationTypeByCode(value)
                    .orElseThrow(() -> problem("RULE_SELECTOR_INVALID", "Operation type is not available"));
            if (!type.enabled()) {
                throw problem("OPERATION_TYPE_DISABLED", "Operation type is disabled");
            }
            normalized.add(type.code());
        }
        return normalized;
    }

    private Measure requireMeasure(Measure measure) {
        if (measure == null) {
            throw problem("VALIDATION_ERROR", "measure must not be null");
        }
        requireEnum(measure.metric(), "measure.metric");
        return measure;
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
