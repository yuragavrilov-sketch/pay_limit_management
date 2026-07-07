package ru.copperside.paylimits.management.limitrule.adapter.out.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.limitrule.application.RuleManifestCanonicalJson;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CompiledRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifest;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestPayload;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestStatus;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PostgresRuleManifestRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "limit_management");
        registry.add("spring.flyway.default-schema", () -> "limit_management");
    }

    @Autowired
    private PostgresLimitRuleRepository ruleRepository;

    @Autowired
    private PostgresRuleManifestRepository manifestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RuleManifestCanonicalJson canonicalJson = new RuleManifestCanonicalJson();

    @BeforeEach
    void cleanMutableTables() {
        jdbcTemplate.update("delete from limit_management.rule_manifest_rules");
        jdbcTemplate.update("delete from limit_management.rule_manifests");
        jdbcTemplate.update("delete from limit_management.limit_rules");
    }

    @Test
    void listsOnlyActiveRulesForCompilation() {
        Instant now = Instant.parse("2026-05-28T09:00:00Z");
        LimitRule active = rule("RULE_ACTIVE_MANIFEST", 1, RuleStatus.ACTIVE, now);
        LimitRule draft = rule("RULE_DRAFT_MANIFEST", 1, RuleStatus.DRAFT, now);
        ruleRepository.saveRule(active);
        ruleRepository.saveRule(draft);

        List<LimitRule> rules = manifestRepository.listActiveRulesForCompilation();

        assertThat(rules).extracting(LimitRule::code).containsExactly(active.code());
    }

    @Test
    void savesReadsLatestAndAllocatesNextManifestVersion() {
        Instant now = Instant.parse("2026-05-28T09:00:00Z");
        LimitRule rule = rule("RULE_MANIFEST_VERSION", 1, RuleStatus.ACTIVE, now);
        ruleRepository.saveRule(rule);

        RuleManifest saved = manifestRepository.saveCompiledManifest((version, activeRules, dictionaries) -> manifest(rule, version));
        RuleManifest next = manifestRepository.saveCompiledManifest((version, activeRules, dictionaries) -> manifest(rule, version));

        assertThat(saved.version()).isEqualTo(1);
        assertThat(manifestRepository.findManifest(saved.id())).contains(saved);
        assertThat(manifestRepository.findLatestManifest()).contains(next);
        assertThat(next.version()).isEqualTo(2);
    }

    @Test
    void persistedManifestPayloadRoundTrips() {
        Instant now = Instant.parse("2026-05-28T09:00:00Z");
        LimitRule rule = rule("RULE_MANIFEST_PAYLOAD", 1, RuleStatus.ACTIVE, now);
        ruleRepository.saveRule(rule);
        RuleManifest manifest = manifestRepository.saveCompiledManifest((version, activeRules, dictionaries) -> manifest(rule, version));

        RuleManifest found = manifestRepository.findManifest(manifest.id()).orElseThrow();

        assertThat(found.payload()).isEqualTo(manifest.payload());
        assertThat(found.ruleCount()).isEqualTo(manifest.payload().ruleCount());
        assertThat(found.rules()).isEqualTo(manifest.payload().rules());
    }

    @Test
    void compilesManifestUsingRepeatableReadSnapshot() {
        Instant now = Instant.parse("2026-05-28T09:00:00Z");
        LimitRule rule = rule("RULE_MANIFEST_SNAPSHOT", 1, RuleStatus.ACTIVE, now);
        ruleRepository.saveRule(rule);

        manifestRepository.saveCompiledManifest((version, activeRules, dictionaries) -> {
            String isolation = jdbcTemplate.queryForObject("show transaction_isolation", String.class);
            assertThat(isolation).isEqualTo("repeatable read");
            return manifest(rule, version);
        });
    }

    @Test
    void rejectsPayloadDriftBeforeInsert() {
        Instant now = Instant.parse("2026-05-28T09:00:00Z");
        LimitRule rule = rule("RULE_MANIFEST_DRIFT", 1, RuleStatus.ACTIVE, now);
        ruleRepository.saveRule(rule);

        assertThatThrownBy(() -> manifestRepository.saveCompiledManifest((version, activeRules, dictionaries) -> {
            RuleManifest manifest = manifest(rule, version);
            RuleManifestPayload payload = manifest.payload();
            RuleManifestPayload driftedPayload = new RuleManifestPayload(
                    payload.version() + 1,
                    payload.status(),
                    payload.ruleCount(),
                    payload.createdAt(),
                    payload.rules(),
                    payload.diagnostics()
            );
            return new RuleManifest(
                    manifest.id(),
                    manifest.version(),
                    manifest.status(),
                    manifest.checksum(),
                    manifest.ruleCount(),
                    manifest.createdAt(),
                    manifest.rules(),
                    manifest.diagnostics(),
                    driftedPayload
            );
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");

        assertThat(manifestRepository.findLatestManifest()).isEmpty();
    }

    @Test
    void rejectsPayloadRuleCountThatDoesNotMatchRuleListSize() {
        Instant now = Instant.parse("2026-05-28T09:00:00Z");
        LimitRule rule = rule("RULE_MANIFEST_RULE_COUNT", 1, RuleStatus.ACTIVE, now);
        ruleRepository.saveRule(rule);

        assertThatThrownBy(() -> manifestRepository.saveCompiledManifest((version, activeRules, dictionaries) -> {
            RuleManifest manifest = manifest(rule, version);
            RuleManifestPayload payload = manifest.payload();
            RuleManifestPayload driftedPayload = new RuleManifestPayload(
                    payload.version(),
                    payload.status(),
                    payload.ruleCount() + 1,
                    payload.createdAt(),
                    payload.rules(),
                    payload.diagnostics()
            );
            return new RuleManifest(
                    manifest.id(),
                    manifest.version(),
                    manifest.status(),
                    canonicalJson.checksum(driftedPayload),
                    driftedPayload.ruleCount(),
                    manifest.createdAt(),
                    manifest.rules(),
                    manifest.diagnostics(),
                    driftedPayload
            );
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule count");

        assertThat(manifestRepository.findLatestManifest()).isEmpty();
    }

    private RuleManifest manifest(LimitRule rule, int version) {
        CompiledRule compiled = new CompiledRule(
                rule.id(),
                rule.code(),
                rule.version(),
                new CompiledRule.Matcher(
                        rule.operationTypes().stream().sorted().toList(),
                        rule.direction(),
                        rule.attributeSelector(),
                        rule.limitTargetType()
                ),
                rule.measure(),
                rule.limitValue(),
                rule.errorMessageTemplate()
        );
        RuleManifestPayload payload = new RuleManifestPayload(
                version,
                RuleManifestStatus.VALID,
                1,
                Instant.parse("2026-05-28T09:00:00Z"),
                List.of(compiled),
                List.of()
        );
        return new RuleManifest(
                UUID.randomUUID(),
                version,
                RuleManifestStatus.VALID,
                canonicalJson.checksum(payload),
                1,
                payload.createdAt(),
                payload.rules(),
                payload.diagnostics(),
                payload
        );
    }

    private LimitRule rule(String code, int version, RuleStatus status, Instant now) {
        Instant activatedAt = status == RuleStatus.ACTIVE ? now : null;
        return new LimitRule(
                UUID.randomUUID(),
                code,
                version,
                code,
                Set.of("SBP_C2B"),
                OperationDirection.IN,
                new Measure(RuleMetric.AMOUNT, RulePeriod.DAY, AggregationScope.OWNER, "RUB", null),
                // OWNER scope must not carry a limitTargetType (validation 4 / V13 DB check).
                null,
                new BigDecimal("1000.00"),
                "template",
                new RuleSelector<>(AttributeSelectorType.NONE, null),
                status,
                now,
                now,
                activatedAt,
                null
        );
    }
}
