package ru.copperside.paylimits.management.runtimeconfig.application;

import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledRule;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestPayload;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeOperationType;
import ru.copperside.paylimits.management.runtimeconfig.domain.wire.AssignmentV2;
import ru.copperside.paylimits.management.runtimeconfig.domain.wire.AttributeSelectorV2;
import ru.copperside.paylimits.management.runtimeconfig.domain.wire.ManifestDocumentV2;
import ru.copperside.paylimits.management.runtimeconfig.domain.wire.MeasureV2;
import ru.copperside.paylimits.management.runtimeconfig.domain.wire.MembershipV2;
import ru.copperside.paylimits.management.runtimeconfig.domain.wire.OperationTypeV2;
import ru.copperside.paylimits.management.runtimeconfig.domain.wire.OwnerV2;
import ru.copperside.paylimits.management.runtimeconfig.domain.wire.RuleV2;

import java.math.BigDecimal;
import java.util.List;

/**
 * Projects the internal compiled {@link RuntimeManifestPayload} onto the engine-facing
 * {@link ManifestDocumentV2} wire model (tech-spec §4.3). Deterministic and order-preserving: the
 * compiler already sorts every collection, so this mapper keeps that order untouched — the checksum
 * over the resulting document is therefore stable for a given canonical snapshot.
 *
 * <p>Mapping is applied ONLY at serialization boundaries (checksum + HTTP response). The internal
 * records, the compile-time invariant re-check and rollback all continue to operate on the internal
 * payload, unchanged.
 */
public final class ManifestDocumentV2Mapper {

    private ManifestDocumentV2Mapper() {
    }

    public static ManifestDocumentV2 toDocument(RuntimeManifestPayload payload) {
        return new ManifestDocumentV2(
                payload.schemaVersion(),
                payload.version(),
                payload.effectiveFrom(),
                payload.businessTimezone(),
                payload.operationTypes().stream().map(ManifestDocumentV2Mapper::toOperationType).toList(),
                payload.rules().stream().map(ManifestDocumentV2Mapper::toRule).toList(),
                payload.assignments().stream().map(ManifestDocumentV2Mapper::toAssignment).toList(),
                payload.memberships().stream().map(ManifestDocumentV2Mapper::toMembership).toList()
        );
    }

    private static OperationTypeV2 toOperationType(RuntimeOperationType operationType) {
        return new OperationTypeV2(
                operationType.code(),
                operationType.direction().name(),
                operationType.counterpartyType().name()
        );
    }

    private static RuleV2 toRule(RuntimeCompiledRule rule) {
        RuntimeCompiledRule.Matcher matcher = rule.matcher();
        return new RuleV2(
                rule.ruleId(),
                rule.code(),
                rule.version(),
                toMeasure(rule.measure()),
                toLimitValue(rule.limitValue()),
                List.copyOf(matcher.operationTypes()),
                matcher.direction() == null ? null : matcher.direction().name(),
                matcher.targetType() == null ? null : matcher.targetType().name(),
                rule.errorMessageTemplate(),
                toAttributeSelector(matcher)
        );
    }

    private static MeasureV2 toMeasure(Measure measure) {
        return new MeasureV2(
                measure.metric() == null ? null : measure.metric().name(),
                measure.period() == null ? null : measure.period().name(),
                measure.aggregationScope() == null ? null : measure.aggregationScope().name(),
                measure.currency(),
                measure.intervalMinutes()
        );
    }

    private static AttributeSelectorV2 toAttributeSelector(RuntimeCompiledRule.Matcher matcher) {
        if (matcher.attribute() == null) {
            return null;
        }
        return new AttributeSelectorV2(
                matcher.attribute().type() == null ? null : matcher.attribute().type().name(),
                matcher.attribute().value()
        );
    }

    private static String toLimitValue(BigDecimal limitValue) {
        return limitValue == null ? null : limitValue.toPlainString();
    }

    private static AssignmentV2 toAssignment(RuntimeCompiledAssignment assignment) {
        return new AssignmentV2(
                assignment.assignmentId(),
                assignment.ruleId(),
                toOwner(assignment.ownerType(), assignment.ownerId()),
                assignment.limitMode() == null ? null : assignment.limitMode().name(),
                assignment.validFrom(),
                assignment.validTo()
        );
    }

    private static OwnerV2 toOwner(AssignmentOwnerType ownerType, String ownerId) {
        String level = ownerType == null ? null : ownerType.name();
        // GLOBAL null-owner_id sentinel (see also PostgresLimitAssignmentRepository.hasEnabledOverlap
        // and V11__global_assignment_level.sql): GLOBAL owners carry no id on the wire, so this maps
        // straight to null. ownerId is already null for GLOBAL at this point, kept explicit for clarity.
        String id = ownerType == AssignmentOwnerType.GLOBAL ? null : ownerId;
        return new OwnerV2(level, id);
    }

    private static MembershipV2 toMembership(RuntimeMerchantGroupMembership membership) {
        return new MembershipV2(
                membership.membershipId(),
                membership.groupId(),
                membership.merchantId(),
                membership.validFrom(),
                membership.validTo()
        );
    }
}
