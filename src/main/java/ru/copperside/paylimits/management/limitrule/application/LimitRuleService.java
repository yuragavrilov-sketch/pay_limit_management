package ru.copperside.paylimits.management.limitrule.application;

import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class LimitRuleService {

    private static final String TARGET_TYPE_PHONE = "PHONE";

    private final LimitRuleRepository repository;
    private final Clock clock;

    public LimitRuleService(LimitRuleRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
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
                now,
                now
        ));
    }

    public OperationType patchOperationType(UUID id, PatchOperationTypeCommand command) {
        requireCommand(command);
        OperationType existing = repository.findOperationType(requireUuid(id, "operationTypeId"))
                .orElseThrow(() -> problem("OPERATION_TYPE_NOT_FOUND", "Operation type not found"));
        OperationDirection direction = command.direction() == null ? existing.direction() : command.direction();
        if (direction != existing.direction() && repository.hasActiveRulesForOperationType(existing.id())) {
            throw problem("OPERATION_TYPE_IN_USE", "Operation type is used by active rules");
        }
        OperationType updated = new OperationType(
                existing.id(),
                existing.code(),
                command.name() == null ? existing.name() : requireText(command.name(), "name"),
                command.familyCode() == null ? existing.familyCode() : requireText(command.familyCode(), "familyCode"),
                direction,
                command.enabled() == null ? existing.enabled() : command.enabled(),
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
        OperationType type = requireEnabledOperationType(command.operationTypeId());
        RuleMetric metric = requireEnum(command.metric(), "metric");
        RulePeriod period = requireEnum(command.period(), "period");
        Instant now = Instant.now(clock);
        return repository.saveRule(new LimitRule(
                UUID.randomUUID(),
                code,
                repository.nextVersion(code),
                name,
                type.id(),
                type.code(),
                type.direction(),
                TARGET_TYPE_PHONE,
                metric,
                period,
                currencyFor(metric),
                RuleStatus.DRAFT,
                now,
                now,
                null,
                null
        ));
    }

    public LimitRule patchRule(UUID id, PatchLimitRuleCommand command) {
        requireCommand(command);
        LimitRule existing = getRule(id);
        requireDraft(existing);
        OperationType type = command.operationTypeId() == null
                ? requireEnabledOperationType(existing.operationTypeId())
                : requireEnabledOperationType(command.operationTypeId());
        RuleMetric metric = command.metric() == null ? existing.metric() : command.metric();
        RulePeriod period = command.period() == null ? existing.period() : command.period();
        LimitRule updated = new LimitRule(
                existing.id(),
                existing.code(),
                existing.version(),
                command.name() == null ? existing.name() : requireText(command.name(), "name"),
                type.id(),
                type.code(),
                type.direction(),
                existing.targetType(),
                metric,
                period,
                currencyFor(metric),
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
        requireEnabledOperationType(existing.operationTypeId());
        Instant now = Instant.now(clock);
        LimitRule updated = new LimitRule(
                existing.id(),
                existing.code(),
                existing.version(),
                existing.name(),
                existing.operationTypeId(),
                existing.operationTypeCode(),
                existing.direction(),
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
                existing.operationTypeId(),
                existing.operationTypeCode(),
                existing.direction(),
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
        if (existing.status() != RuleStatus.ACTIVE) {
            throw problem("RULE_STATUS_CONFLICT", "Only active rules can be versioned");
        }
        repository.findDraftByCode(existing.code())
                .ifPresent(rule -> {
                    throw problem("RULE_DRAFT_EXISTS", "Draft rule already exists");
                });
        OperationType type = requireEnabledOperationType(existing.operationTypeId());
        Instant now = Instant.now(clock);
        return repository.saveRule(new LimitRule(
                UUID.randomUUID(),
                existing.code(),
                repository.nextVersion(existing.code()),
                existing.name(),
                type.id(),
                type.code(),
                type.direction(),
                existing.targetType(),
                existing.metric(),
                existing.period(),
                currencyFor(existing.metric()),
                RuleStatus.DRAFT,
                now,
                now,
                null,
                null
        ));
    }

    private String currencyFor(RuleMetric metric) {
        return metric == RuleMetric.AMOUNT ? "RUB" : null;
    }

    private void requireDraft(LimitRule rule) {
        if (rule.status() != RuleStatus.DRAFT) {
            throw problem("RULE_STATUS_CONFLICT", "Only draft rules can be edited");
        }
    }

    private OperationType requireEnabledOperationType(UUID operationTypeId) {
        OperationType type = repository.findOperationType(requireUuid(operationTypeId, "operationTypeId"))
                .orElseThrow(() -> problem("OPERATION_TYPE_NOT_FOUND", "Operation type not found"));
        if (!type.enabled()) {
            throw problem("OPERATION_TYPE_DISABLED", "Operation type is disabled");
        }
        return type;
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
