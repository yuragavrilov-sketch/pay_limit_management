package ru.copperside.paylimits.management.limitrule.application.port.out;

import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LimitRuleRepository {
    List<OperationType> listOperationTypes();

    RuleDictionaries getRuleDictionaries();

    Optional<OperationType> findOperationType(UUID id);

    Optional<OperationType> findOperationTypeByCode(String code);

    boolean operationFamilyExists(String code);

    boolean attributeValueExists(AttributeSelectorType type, String code);

    OperationType saveOperationType(OperationType type);

    OperationType updateOperationType(OperationType type);

    boolean hasActiveRulesForOperationTypeCode(String operationTypeCode);

    List<LimitRule> listRules();

    Optional<LimitRule> findRule(UUID id);

    Optional<LimitRule> findDraftByCode(String code);

    Optional<LimitRule> findActiveByCode(String code);

    int nextVersion(String code);

    LimitRule saveRule(LimitRule rule);

    LimitRule updateRule(LimitRule rule);
}
