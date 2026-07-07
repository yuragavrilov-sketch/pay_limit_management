package ru.copperside.paylimits.management.runtimeconfig.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MGT-I-18 (spec §8, plan stage 7 / task 5): the {@code V15__seed_first_group.sql} data migration
 * seeds the first merchant group ("ПОД/ФТ выплаты") and its 11 payout-limit rules as DRAFT,
 * not-enabled entities for MANUAL operator activation. Confirms Flyway on a clean DB leaves:
 * (a) the seed group type + group in place, (b) exactly the 11 expected DRAFT rule codes with
 * none of them ACTIVE, (c) the seed group assignments not enabled, and (d) compiling a runtime
 * manifest right after the migration runs never includes any of the seeded (DRAFT) rules, since
 * compilation only reads {@code status = 'ACTIVE'} rules. Synthetic literal identifiers only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.context.annotation.Import(ru.copperside.paylimits.management.audit.OperatorHeaderTestConfig.class)
class SeedFirstGroupIntegrationTest {

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

    private static final List<String> EXPECTED_RULE_CODES = List.of(
            "PODFT-CARD-COUNT-DAY",
            "PODFT-PHONE-COUNT-DAY",
            "PODFT-CARD-COUNT-MONTH",
            "PODFT-PHONE-COUNT-MONTH",
            "PODFT-AMOUNT-PER-OPERATION",
            "PODFT-CARD-AMOUNT-DAY",
            "PODFT-PHONE-AMOUNT-DAY",
            "PODFT-CARD-AMOUNT-MONTH",
            "PODFT-PHONE-AMOUNT-MONTH",
            "PODFT-CARD-INTERVAL",
            "PODFT-PHONE-INTERVAL"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void seedGroupTypeAndGroupExist() {
        Integer typeCount = jdbcTemplate.queryForObject("""
                select count(*) from limit_management.merchant_group_types where code = 'PODFT'
                """, Integer.class);
        assertThat(typeCount).isEqualTo(1);

        Integer groupCount = jdbcTemplate.queryForObject("""
                select count(*) from limit_management.merchant_groups where code = 'PODFT-PAYOUTS' and enabled = true
                """, Integer.class);
        assertThat(groupCount).isEqualTo(1);
    }

    @Test
    void seedRulesAreExactlyTheExpectedCodesAllDraftAndNoneActive() {
        List<String> seededCodes = jdbcTemplate.queryForList("""
                select code from limit_management.limit_rules where code like 'PODFT-%' order by code
                """, String.class);
        assertThat(seededCodes).containsExactlyInAnyOrderElementsOf(EXPECTED_RULE_CODES);

        Integer draftCount = jdbcTemplate.queryForObject("""
                select count(*) from limit_management.limit_rules
                where code like 'PODFT-%' and status = 'DRAFT' and activated_at is null and disabled_at is null
                """, Integer.class);
        assertThat(draftCount).isEqualTo(EXPECTED_RULE_CODES.size());

        Integer activeCount = jdbcTemplate.queryForObject("""
                select count(*) from limit_management.limit_rules where code like 'PODFT-%' and status = 'ACTIVE'
                """, Integer.class);
        assertThat(activeCount).isZero();
    }

    @Test
    void seedGroupAssignmentsAreNotEnabled() {
        Integer assignmentCount = jdbcTemplate.queryForObject("""
                select count(*)
                from limit_management.limit_assignments a
                join limit_management.limit_rules r on r.id = a.rule_id
                join limit_management.merchant_groups g on g.code = 'PODFT-PAYOUTS'
                where r.code like 'PODFT-%'
                  and a.owner_type = 'MERCHANT_GROUP'
                  and a.owner_id = g.id::text
                """, Integer.class);
        assertThat(assignmentCount).isEqualTo(EXPECTED_RULE_CODES.size());

        Integer enabledCount = jdbcTemplate.queryForObject("""
                select count(*)
                from limit_management.limit_assignments a
                join limit_management.limit_rules r on r.id = a.rule_id
                where r.code like 'PODFT-%' and a.enabled = true
                """, Integer.class);
        assertThat(enabledCount).isZero();
    }

    @Test
    void compilingManifestRightAfterSeedIncludesNoneOfTheSeededDraftRules() throws Exception {
        Instant effectiveFrom = Instant.now().plus(Duration.ofMinutes(10));
        String manifestBody = mockMvc.perform(post("/internal/v1/limit-management/runtime-manifests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "effectiveFrom": "%s" }
                                """.formatted(effectiveFrom)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode rules = objectMapper.readTree(manifestBody).at("/data/document/rules");
        List<String> compiledCodes = rules.findValuesAsText("code");
        assertThat(compiledCodes).noneMatch(code -> code.startsWith("PODFT-"));
    }
}
