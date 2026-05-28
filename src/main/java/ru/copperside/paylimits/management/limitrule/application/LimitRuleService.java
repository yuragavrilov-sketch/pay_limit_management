package ru.copperside.paylimits.management.limitrule.application;

import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class LimitRuleService {

    private static final String DEFAULT_CURRENCY = "RUB";

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
        RuleMetric metric = requireEnum(command.metric(), "metric");
        Instant now = Instant.now(clock);
        LimitRule rule = new LimitRule(
                UUID.randomUUID(),
                code,
                repository.nextVersion(code),
                requireText(command.name(), "name"),
                validateOperationSelector(command.operationSelector()),
                requireEnum(command.direction(), "direction"),
                validateAttributeSelector(command.attributeSelector()),
                requireEnum(command.targetType(), "targetType"),
                metric,
                requireEnum(command.period(), "period"),
                normalizeCurrency(metric, command.currency(), true),
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
        RuleMetric metric = command.metric() == null ? existing.metric() : command.metric();
        String currency = normalizePatchCurrency(existing, metric, command.currency());
        LimitRule updated = new LimitRule(
                existing.id(),
                existing.code(),
                existing.version(),
                command.name() == null ? existing.name() : requireText(command.name(), "name"),
                command.operationSelector() == null
                        ? existing.operationSelector()
                        : validateOperationSelector(command.operationSelector()),
                command.direction() == null ? existing.direction() : command.direction(),
                command.attributeSelector() == null
                        ? existing.attributeSelector()
                        : validateAttributeSelector(command.attributeSelector()),
                command.targetType() == null ? existing.targetType() : command.targetType(),
                metric,
                command.period() == null ? existing.period() : command.period(),
                currency,
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
        validateOperationSelector(existing.operationSelector());
        validateAttributeSelector(existing.attributeSelector());
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
                existing.operationSelector(),
                existing.direction(),
                existing.attributeSelector(),
                existing.targetType(),
                existing.metric(),
                existing.period(),
                existing.currency(),
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
                existing.operationSelector(),
                existing.direction(),
                existing.attributeSelector(),
                existing.targetType(),
                existing.metric(),
                existing.period(),
                existing.currency(),
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
                existing.operationSelector(),
                existing.direction(),
                existing.attributeSelector(),
                existing.targetType(),
                existing.metric(),
                existing.period(),
                existing.currency(),
                RuleStatus.DRAFT,
                now,
                now,
                null,
                null
        ));
    }

    private RuleSelector<OperationSelectorType> validateOperationSelector(RuleSelector<OperationSelectorType> selector) {
        RuleSelector<OperationSelectorType> normalized = requireSelector(selector, "operationSelector");
        return switch (normalized.type()) {
            case ANY -> {
                requireNoSelectorValue(normalized, "operationSelector");
                yield new RuleSelector<>(OperationSelectorType.ANY, null);
            }
            case FAMILY -> {
                String value = requireText(normalized.value(), "operationSelector.value");
                if (!repository.operationFamilyExists(value)) {
                    throw problem("RULE_SELECTOR_INVALID", "Operation family is not available");
                }
                yield new RuleSelector<>(OperationSelectorType.FAMILY, value);
            }
            case TYPE -> {
                String value = requireText(normalized.value(), "operationSelector.value");
                OperationType type = repository.findOperationTypeByCode(value)
                        .orElseThrow(() -> problem("RULE_SELECTOR_INVALID", "Operation type is not available"));
                if (!type.enabled()) {
                    throw problem("OPERATION_TYPE_DISABLED", "Operation type is disabled");
                }
                yield new RuleSelector<>(OperationSelectorType.TYPE, type.code());
            }
        };
    }

    private RuleSelector<AttributeSelectorType> validateAttributeSelector(RuleSelector<AttributeSelectorType> selector) {
        RuleSelector<AttributeSelectorType> normalized = requireSelector(selector, "attributeSelector");
        if (normalized.type() == AttributeSelectorType.NONE) {
            requireNoSelectorValue(normalized, "attributeSelector");
            return new RuleSelector<>(AttributeSelectorType.NONE, null);
        }
        String value = requireText(normalized.value(), "attributeSelector.value");
        if (!repository.attributeValueExists(normalized.type(), value)) {
            throw problem("RULE_SELECTOR_INVALID", "Attribute selector value is not available");
        }
        return new RuleSelector<>(normalized.type(), value);
    }

    private <T extends Enum<T>> RuleSelector<T> requireSelector(RuleSelector<T> selector, String field) {
        if (selector == null || selector.type() == null) {
            throw problem("VALIDATION_ERROR", field + ".type must not be null");
        }
        return selector;
    }

    private <T extends Enum<T>> void requireNoSelectorValue(RuleSelector<T> selector, String field) {
        if (selector.value() != null && !selector.value().isBlank()) {
            throw problem("VALIDATION_ERROR", field + ".value must be null");
        }
    }

    private String normalizePatchCurrency(LimitRule existing, RuleMetric metric, String currency) {
        if (currency != null) {
            return normalizeCurrency(metric, currency, true);
        }
        if (metric == RuleMetric.COUNT) {
            return null;
        }
        return existing.metric() == RuleMetric.AMOUNT && existing.currency() != null
                ? existing.currency()
                : DEFAULT_CURRENCY;
    }

    private String normalizeCurrency(RuleMetric metric, String currency, boolean required) {
        if (metric == RuleMetric.COUNT) {
            if (currency != null && !currency.isBlank()) {
                throw problem("VALIDATION_ERROR", "currency must be null for COUNT rules");
            }
            return null;
        }
        if (currency == null || currency.isBlank()) {
            if (required) {
                throw problem("VALIDATION_ERROR", "currency must not be blank for AMOUNT rules");
            }
            return DEFAULT_CURRENCY;
        }
        String normalized = currency.trim().toUpperCase();
        if (!DEFAULT_CURRENCY.equals(normalized)) {
            throw problem("VALIDATION_ERROR", "Only RUB currency is supported");
        }
        return normalized;
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
