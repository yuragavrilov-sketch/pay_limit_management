package ru.copperside.paylimits.management.runtimeconfig.domain.wire;

import java.time.Instant;
import java.util.List;

/**
 * Engine-facing runtime-manifest document, shaped exactly per tech-spec §4.3. This is the wire
 * contract that is (a) hashed to produce the manifest checksum, and (b) returned to engine on GET.
 *
 * <p>The checksum is computed over the canonical JSON of THIS document (all keys sorted
 * alphabetically at every level, ISO-8601 instants, explicit nulls serialized), and does NOT include
 * a {@code checksum} field itself — the checksum is carried alongside the document in the HTTP
 * response, never inside the hashed body.
 *
 * <p>Deviation from §4.3: {@code rules[].attributeSelector} is a deliberate extension (see the M1
 * schema doc). Everything else matches §4.3 field-for-field.
 */
public record ManifestDocumentV2(
        int schemaVersion,
        int manifestVersion,
        Instant effectiveFrom,
        String businessTimezone,
        List<OperationTypeV2> operationTypes,
        List<RuleV2> rules,
        List<AssignmentV2> assignments,
        List<MembershipV2> memberships
) {
    public ManifestDocumentV2 {
        operationTypes = operationTypes == null ? List.of() : List.copyOf(operationTypes);
        rules = rules == null ? List.of() : List.copyOf(rules);
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        memberships = memberships == null ? List.of() : List.copyOf(memberships);
    }
}
