package ru.copperside.paylimits.management.merchantgroup.adapter.out.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
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
                .hasMessageContaining("merchant_group_memberships_no_overlap");
    }
}
