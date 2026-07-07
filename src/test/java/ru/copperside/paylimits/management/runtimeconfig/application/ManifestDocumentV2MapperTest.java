package ru.copperside.paylimits.management.runtimeconfig.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.ManifestDiagnostic;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledRule;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestPayload;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestStatus;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeOperationType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shape test for the engine-facing v2 wire document (tech-spec §4.3). Serializes the mapped document
 * through the canonical serializer and asserts the emitted JSON matches §4.3 field-for-field.
 */
class ManifestDocumentV2MapperTest {

    private final RuntimeManifestCanonicalJson canonicalJson = new RuntimeManifestCanonicalJson();
    private final ObjectMapper reader = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private static final UUID RULE_ID = UUID.fromString("0d9f1c2e-0000-4000-8000-000000000001");
    private static final UUID GLOBAL_ASSIGNMENT_ID = UUID.fromString("7a3bd1a0-0000-4000-8000-000000000003");
    private static final UUID MERCHANT_ASSIGNMENT_ID = UUID.fromString("7a3bd1a0-0000-4000-8000-000000000002");
    private static final UUID MEMBERSHIP_ID = UUID.fromString("c1d2e3f4-0000-4000-8000-000000000004");
    private static final UUID GROUP_TYPE_ID = UUID.fromString("aaaa1111-0000-4000-8000-000000000005");
    private static final UUID GROUP_ID = UUID.fromString("bbbb2222-0000-4000-8000-000000000006");

    private JsonNode document() throws Exception {
        return reader.readTree(canonicalJson.documentBytes(samplePayload()));
    }

    @Test
    void topLevelUsesManifestVersionNotVersion() throws Exception {
        JsonNode doc = document();

        assertThat(doc.has("manifestVersion")).isTrue();
        assertThat(doc.get("manifestVersion").asInt()).isEqualTo(42);
        assertThat(doc.has("version")).isFalse();
        assertThat(doc.get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(doc.get("businessTimezone").asText()).isEqualTo("Europe/Moscow");
        assertThat(doc.get("effectiveFrom").asText()).isEqualTo("2026-07-10T00:00:00Z");
    }

    @Test
    void ruleIsFlatWithNoMatcherWrapperAndStringLimitValue() throws Exception {
        JsonNode rule = document().get("rules").get(0);

        assertThat(rule.has("matcher")).isFalse();
        assertThat(rule.has("operationTypes")).isTrue();
        assertThat(rule.get("operationTypes").get(0).asText()).isEqualTo("OCT");
        assertThat(rule.get("direction").asText()).isEqualTo("OUT");
        assertThat(rule.get("limitTargetType").asText()).isEqualTo("CARD");
        assertThat(rule.get("errorMessageTemplate").asText()).isEqualTo("Limit exceeded: %d of %f (%s)");
        assertThat(rule.get("measure").get("metric").asText()).isEqualTo("COUNT");
        assertThat(rule.get("measure").get("currency").isNull()).isTrue();
        // Money is a decimal string, not a JSON number.
        assertThat(rule.get("limitValue").isTextual()).isTrue();
        assertThat(rule.get("limitValue").asText()).isEqualTo("3");
        // attributeSelector is the sole extension beyond §4.3.
        assertThat(rule.get("attributeSelector").get("type").asText()).isEqualTo("NONE");
        assertThat(rule.get("attributeSelector").get("value").isNull()).isTrue();
    }

    @Test
    void assignmentUsesOwnerObjectAndGlobalOwnerHasNullId() throws Exception {
        JsonNode global = assignmentById(GLOBAL_ASSIGNMENT_ID);
        JsonNode merchant = assignmentById(MERCHANT_ASSIGNMENT_ID);

        assertThat(document().get("assignments")).hasSize(2);
        // No flat owner fields, no ruleCode leaked to the wire.
        assertThat(global.has("ownerType")).isFalse();
        assertThat(global.has("ownerId")).isFalse();
        assertThat(global.has("ruleCode")).isFalse();
        assertThat(global.has("limitMode")).isFalse();
        assertThat(global.has("validFrom")).isFalse();

        assertThat(global.get("owner").get("level").asText()).isEqualTo("GLOBAL");
        assertThat(global.get("owner").get("id").isNull()).isTrue();
        assertThat(global.get("mode").asText()).isEqualTo("LIMITED");
        assertThat(global.get("activeFrom").asText()).isEqualTo("2026-07-01T00:00:00Z");

        assertThat(merchant.get("owner").get("level").asText()).isEqualTo("MERCHANT");
        assertThat(merchant.get("owner").get("id").asText()).isEqualTo("502118");
    }

    @Test
    void membershipHasActiveFromAndDropsGroupTypeId() throws Exception {
        JsonNode membership = document().get("memberships").get(0);

        assertThat(membership.has("groupTypeId")).isFalse();
        assertThat(membership.has("validFrom")).isFalse();
        assertThat(membership.get("membershipId").asText()).isEqualTo(MEMBERSHIP_ID.toString());
        assertThat(membership.get("groupId").asText()).isEqualTo(GROUP_ID.toString());
        assertThat(membership.get("merchantId").asText()).isEqualTo("502118");
        assertThat(membership.get("activeFrom").asText()).isEqualTo("2026-07-10T00:00:00Z");
        assertThat(membership.get("activeTo").isNull()).isTrue();
    }

    @Test
    void intervalRuleEmitsNullLimitValue() {
        RuntimeCompiledRule intervalRule = new RuntimeCompiledRule(
                RULE_ID,
                "INTERVAL_RULE",
                1,
                new RuntimeCompiledRule.Matcher(
                        List.of("SBP_C2B"),
                        OperationDirection.IN,
                        new RuleSelector<>(AttributeSelectorType.NONE, null),
                        null),
                new Measure(RuleMetric.INTERVAL, RulePeriod.PER_OPERATION, null, null, 15),
                null,
                "template");
        RuntimeManifestPayload payload = new RuntimeManifestPayload(
                2, "Europe/Moscow", List.of(), 1, RuntimeManifestStatus.VALID,
                Instant.parse("2026-07-06T12:00:00Z"), Instant.parse("2026-07-10T00:00:00Z"),
                1, 0, 0, List.of(intervalRule), List.of(), List.of(), List.of());

        var doc = ManifestDocumentV2Mapper.toDocument(payload);

        assertThat(doc.rules().get(0).limitValue()).isNull();
        assertThat(doc.rules().get(0).measure().intervalMinutes()).isEqualTo(15);
        assertThat(doc.rules().get(0).limitTargetType()).isNull();
    }

    @Test
    void checksumIsComputedOverTheDocument() {
        RuntimeManifestPayload payload = samplePayload();
        assertThat(canonicalJson.checksum(payload))
                .isEqualTo(canonicalJson.checksum(ManifestDocumentV2Mapper.toDocument(payload)));
    }

    /**
     * Golden vector — pins the ABSOLUTE canonical bytes and checksum documented in the M1 engine
     * contract (docs/superpowers/specs/2026-07-07-manifest-v2-schema-M1.md §6) for this exact sample
     * payload. Any accidental change to {@link ManifestDocumentV2Mapper}, the {@code wire} records, or
     * the canonical {@link ObjectMapper} configuration in {@link RuntimeManifestCanonicalJson}
     * (field rename, nesting change, null handling) will change these bytes/checksum and fail this
     * test loudly — per CLAUDE.md, any canonicalization change requires a conscious
     * {@code schemaVersion} bump, not a silent drift. If this test fails intentionally (schemaVersion
     * bumped on purpose), regenerate both this vector and the doc §6 example together.
     */
    @Test
    void goldenCanonicalDocumentAndChecksumMatchDocumentedM1Vector() {
        String expectedCanonicalJson = "{\"assignments\":[{\"activeFrom\":\"2026-07-01T00:00:00Z\","
                + "\"activeTo\":null,\"assignmentId\":\"7a3bd1a0-0000-4000-8000-000000000003\","
                + "\"mode\":\"LIMITED\",\"owner\":{\"id\":null,\"level\":\"GLOBAL\"},"
                + "\"ruleId\":\"0d9f1c2e-0000-4000-8000-000000000001\"},"
                + "{\"activeFrom\":\"2026-07-05T00:00:00Z\",\"activeTo\":null,"
                + "\"assignmentId\":\"7a3bd1a0-0000-4000-8000-000000000002\",\"mode\":\"LIMITED\","
                + "\"owner\":{\"id\":\"502118\",\"level\":\"MERCHANT\"},"
                + "\"ruleId\":\"0d9f1c2e-0000-4000-8000-000000000001\"}],"
                + "\"businessTimezone\":\"Europe/Moscow\",\"effectiveFrom\":\"2026-07-10T00:00:00Z\","
                + "\"manifestVersion\":42,\"memberships\":[{\"activeFrom\":\"2026-07-10T00:00:00Z\","
                + "\"activeTo\":null,\"groupId\":\"bbbb2222-0000-4000-8000-000000000006\","
                + "\"membershipId\":\"c1d2e3f4-0000-4000-8000-000000000004\",\"merchantId\":\"502118\"}],"
                + "\"operationTypes\":[{\"code\":\"OCT\",\"counterpartyType\":\"CARD\",\"direction\":\"OUT\"}],"
                + "\"rules\":[{\"attributeSelector\":{\"type\":\"NONE\",\"value\":null},"
                + "\"code\":\"PAYOUT-CARD-COUNT-DAY\",\"direction\":\"OUT\","
                + "\"errorMessageTemplate\":\"Limit exceeded: %d of %f (%s)\",\"limitTargetType\":\"CARD\","
                + "\"limitValue\":\"3\",\"measure\":{\"aggregationScope\":\"TARGET\",\"currency\":null,"
                + "\"intervalMinutes\":null,\"metric\":\"COUNT\",\"period\":\"DAY\"},"
                + "\"operationTypes\":[\"OCT\"],\"ruleId\":\"0d9f1c2e-0000-4000-8000-000000000001\","
                + "\"version\":2}],\"schemaVersion\":2}";
        String expectedChecksum = "sha256:8546c2090d819572eac60fa59b14da985a07c59644fc93bcdf3abe33db4ae168";

        RuntimeManifestPayload payload = samplePayload();
        String actualCanonicalJson = new String(canonicalJson.documentBytes(payload), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(actualCanonicalJson).isEqualTo(expectedCanonicalJson);
        assertThat(canonicalJson.checksum(payload)).isEqualTo(expectedChecksum);
    }

    private JsonNode assignmentById(UUID id) throws Exception {
        for (JsonNode node : document().get("assignments")) {
            if (id.toString().equals(node.get("assignmentId").asText())) {
                return node;
            }
        }
        throw new AssertionError("assignment not found: " + id);
    }

    private RuntimeManifestPayload samplePayload() {
        // Card payout daily COUNT limit per target card: OCT is OUT/CARD, matching the rule's
        // direction and TARGET aggregation scope (validation 4: TARGET scope requires
        // limitTargetType and a single counterparty equal to it).
        RuntimeCompiledRule rule = new RuntimeCompiledRule(
                RULE_ID,
                "PAYOUT-CARD-COUNT-DAY",
                2,
                new RuntimeCompiledRule.Matcher(
                        List.of("OCT"),
                        OperationDirection.OUT,
                        new RuleSelector<>(AttributeSelectorType.NONE, null),
                        LimitTargetType.CARD),
                new Measure(RuleMetric.COUNT, RulePeriod.DAY, AggregationScope.TARGET, null, null),
                new BigDecimal("3"),
                "Limit exceeded: %d of %f (%s)");
        RuntimeCompiledAssignment global = new RuntimeCompiledAssignment(
                GLOBAL_ASSIGNMENT_ID, RULE_ID, "PAYOUT-CARD-COUNT-DAY",
                AssignmentOwnerType.GLOBAL, null, LimitMode.LIMITED,
                Instant.parse("2026-07-01T00:00:00Z"), null);
        RuntimeCompiledAssignment merchant = new RuntimeCompiledAssignment(
                MERCHANT_ASSIGNMENT_ID, RULE_ID, "PAYOUT-CARD-COUNT-DAY",
                AssignmentOwnerType.MERCHANT, "502118", LimitMode.LIMITED,
                Instant.parse("2026-07-05T00:00:00Z"), null);
        RuntimeMerchantGroupMembership membership = new RuntimeMerchantGroupMembership(
                MEMBERSHIP_ID, "502118", GROUP_TYPE_ID, GROUP_ID,
                Instant.parse("2026-07-10T00:00:00Z"), null);
        RuntimeOperationType operationType = new RuntimeOperationType(
                "OCT", OperationDirection.OUT, CounterpartyType.CARD);
        return new RuntimeManifestPayload(
                2,
                "Europe/Moscow",
                List.of(operationType),
                42,
                RuntimeManifestStatus.VALID,
                Instant.parse("2026-07-06T12:00:00Z"),
                Instant.parse("2026-07-10T00:00:00Z"),
                1, 2, 1,
                List.of(rule),
                List.of(global, merchant),
                List.of(membership),
                List.<ManifestDiagnostic>of());
    }
}
