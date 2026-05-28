package ru.copperside.paylimits.management.merchantgroup.adapter.out.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class MerchantGroupSchemaIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesLimitManagementSchema() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'limit_management'
                  and table_name in (
                    'merchant_group_types',
                    'merchant_groups',
                    'merchant_group_memberships'
                  )
                """, Integer.class);

        assertThat(tableCount).isEqualTo(3);
    }

    @Test
    void flywayCreatesOperationTypesAndLimitRulesWithSbpSeed() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'limit_management'
                  and table_name in ('operation_types', 'limit_rules')
                """, Integer.class);

        assertThat(tableCount).isEqualTo(2);

        List<String> operationCodes = jdbcTemplate.queryForList("""
                select code
                from limit_management.operation_types
                order by code asc
                """, String.class);

        assertThat(operationCodes).contains("SBP_B2C", "SBP_C2B");
    }

    @Test
    void flywayCreatesRuleDictionariesAndSelectorRules() {
        Integer dictionaryCount = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'limit_management'
                  and table_name in (
                    'operation_families',
                    'payment_systems',
                    'issuer_countries',
                    'issuer_banks',
                    'bins',
                    'card_types',
                    'card_levels'
                  )
                """, Integer.class);

        assertThat(dictionaryCount).isEqualTo(7);

        List<String> families = jdbcTemplate.queryForList("""
                select code
                from limit_management.operation_families
                order by code asc
                """, String.class);

        assertThat(families).contains("CARD", "SBP");

        List<String> ruleColumns = jdbcTemplate.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = 'limit_management'
                  and table_name = 'limit_rules'
                """, String.class);

        assertThat(ruleColumns)
                .contains(
                        "operation_selector_type",
                        "operation_selector_value",
                        "attribute_selector_type",
                        "attribute_selector_value"
                )
                .doesNotContain("operation_type_id");
    }

    @Test
    void flywayCreatesRuleManifestTables() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'limit_management'
                  and table_name in ('rule_manifests', 'rule_manifest_rules')
                """, Integer.class);

        assertThat(tableCount).isEqualTo(2);

        List<String> manifestColumns = jdbcTemplate.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = 'limit_management'
                  and table_name = 'rule_manifests'
                """, String.class);

        assertThat(manifestColumns)
                .contains("id", "version", "status", "checksum", "rule_count", "payload_json", "created_at");

        List<String> ruleColumns = jdbcTemplate.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = 'limit_management'
                  and table_name = 'rule_manifest_rules'
                """, String.class);

        assertThat(ruleColumns)
                .contains("manifest_id", "rule_id", "rule_code", "rule_version", "position", "payload_json");
    }

    @Test
    void databaseRejectsTwoActiveLimitRuleVersionsForSameCode() {
        jdbcTemplate.update("""
                insert into limit_management.limit_rules
                    (id, code, version, name, operation_selector_type, operation_selector_value, direction,
                     attribute_selector_type, attribute_selector_value, target_type, metric, period, currency,
                     status, created_at, updated_at, activated_at, disabled_at)
                values (?, 'RULE_SBP_C2B_DAY', 1, 'SBP C2B daily amount', 'TYPE', 'SBP_C2B', 'IN',
                        'NONE', null, 'PHONE', 'AMOUNT', 'DAY', 'RUB', 'ACTIVE', now(), now(), now(), null)
                """, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into limit_management.limit_rules
                    (id, code, version, name, operation_selector_type, operation_selector_value, direction,
                     attribute_selector_type, attribute_selector_value, target_type, metric, period, currency,
                     status, created_at, updated_at, activated_at, disabled_at)
                values (?, 'RULE_SBP_C2B_DAY', 2, 'SBP C2B daily amount v2', 'TYPE', 'SBP_C2B', 'IN',
                        'NONE', null, 'PHONE', 'AMOUNT', 'DAY', 'RUB', 'ACTIVE', now(), now(), now(), null)
                """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("limit_rules_one_active_per_code_uk");
    }

    @Test
    void databaseRejectsOverlappingMembershipForSameMerchantAndType() {
        UUID typeId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();

        jdbcTemplate.update("""
                insert into limit_management.merchant_group_types
                    (id, code, name, description, enabled, sort_order, created_at, updated_at)
                values (?, 'risk-tier', 'Risk tier', null, true, 10, now(), now())
                """, typeId);
        jdbcTemplate.update("""
                insert into limit_management.merchant_groups
                    (id, type_id, code, name, description, enabled, created_at, updated_at)
                values (?, ?, 'risk-high', 'High risk', null, true, now(), now())
                """, groupId, typeId);
        jdbcTemplate.update("""
                insert into limit_management.merchant_group_memberships
                    (id, merchant_id, group_id, group_type_id, valid_from, valid_to, created_at, created_by)
                values (?, '502118', ?, ?, ?, null, now(), 'test')
                """, membershipId, groupId, typeId, Timestamp.from(Instant.parse("2026-05-27T09:00:00Z")));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into limit_management.merchant_group_memberships
                    (id, merchant_id, group_id, group_type_id, valid_from, valid_to, created_at, created_by)
                values (?, '502118', ?, ?, ?, null, now(), 'test')
                """, UUID.randomUUID(), groupId, typeId, Timestamp.from(Instant.parse("2026-05-27T10:00:00Z"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("merchant_group_memberships_no_overlap");
    }
}
