package ru.copperside.paylimits.management.limitrule.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record LimitRule(
        UUID id,
        String code,
        int version,
        String name,
        Set<String> operationTypes,
        OperationDirection direction,
        Measure measure,
        LimitTargetType limitTargetType,
        BigDecimal limitValue,
        String errorMessageTemplate,
        RuleSelector<AttributeSelectorType> attributeSelector,
        RuleStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant activatedAt,
        Instant disabledAt
) {
    public LimitRule {
        operationTypes = operationTypes == null ? Set.of() : Set.copyOf(operationTypes);
    }

    public boolean active() {
        return status == RuleStatus.ACTIVE;
    }

    /**
     * ACTIVATE transition (spec lifecycle): status -> ACTIVE, activatedAt/updatedAt -> {@code now}.
     * All other fields — including {@code disabledAt}, which a re-activation-from-DRAFT flow does not
     * touch — are carried over unchanged. Avoids hand-repeating the full 16-arg positional constructor
     * (and the same-typed-field transposition hazard that comes with it) at the one call site that
     * performs this transition.
     */
    public LimitRule activated(Instant now) {
        return new LimitRule(id, code, version, name, operationTypes, direction, measure, limitTargetType,
                limitValue, errorMessageTemplate, attributeSelector, RuleStatus.ACTIVE, createdAt, now, now,
                disabledAt);
    }

    /**
     * DISABLE transition: status -> DISABLED, disabledAt/updatedAt -> {@code now}; {@code activatedAt}
     * is carried over unchanged.
     */
    public LimitRule disabled(Instant now) {
        return new LimitRule(id, code, version, name, operationTypes, direction, measure, limitTargetType,
                limitValue, errorMessageTemplate, attributeSelector, RuleStatus.DISABLED, createdAt, now,
                activatedAt, now);
    }

    /**
     * Factory for the NEW_VERSION transition: a fresh {@code id}/{@code version} DRAFT copy of this
     * rule's definition fields (name, operation types, direction, measure, limit target/value, error
     * template, attribute selector). {@code createdAt}/{@code updatedAt} are set to {@code now};
     * {@code activatedAt}/{@code disabledAt} reset to {@code null} since the new version has not yet
     * been through either transition.
     */
    public LimitRule asNewDraftVersion(UUID newId, int newVersion, Instant now) {
        return new LimitRule(newId, code, newVersion, name, operationTypes, direction, measure, limitTargetType,
                limitValue, errorMessageTemplate, attributeSelector, RuleStatus.DRAFT, now, now, null, null);
    }
}
