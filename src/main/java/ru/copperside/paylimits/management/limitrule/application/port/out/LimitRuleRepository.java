package ru.copperside.paylimits.management.limitrule.application.port.out;

import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LimitRuleRepository {
    List<OperationType> listOperationTypes();

    Optional<OperationType> findOperationType(UUID id);

    OperationType saveOperationType(OperationType type);

    OperationType updateOperationType(OperationType type);

    boolean hasActiveRulesForOperationType(UUID operationTypeId);

    List<LimitRule> listRules();

    Optional<LimitRule> findRule(UUID id);

    Optional<LimitRule> findDraftByCode(String code);

    int nextVersion(String code);

    LimitRule saveRule(LimitRule rule);

    LimitRule updateRule(LimitRule rule);
}
