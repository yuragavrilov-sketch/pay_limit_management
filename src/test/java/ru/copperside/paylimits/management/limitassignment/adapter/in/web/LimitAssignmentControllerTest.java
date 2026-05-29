package ru.copperside.paylimits.management.limitassignment.adapter.in.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.copperside.paylimits.management.limitassignment.application.LimitAssignmentService;
import ru.copperside.paylimits.management.limitassignment.application.port.out.LimitAssignmentRepository;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignment;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitassignment.domain.MerchantGroupReference;
import ru.copperside.paylimits.management.limitassignment.domain.RuleReference;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(LimitAssignmentControllerTest.TestSupport.class)
class LimitAssignmentControllerTest {

    private static final UUID RULE_ID = UUID.fromString("0db59f6a-7f8c-45d6-b6a7-cc1fcb397c6e");
    private static final UUID GROUP_ID = UUID.fromString("4bb2ec8a-0cbb-4a42-8e62-7d4bd8567801");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeRepository repository;

    @BeforeEach
    void setUp() {
        repository.clear();
        repository.addRule(RULE_ID, true);
        repository.addMerchantGroup(GROUP_ID, true);
    }

    @Test
    void listsAssignments() throws Exception {
        repository.addAssignment(RULE_ID, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.UNLIMITED, null,
                Instant.parse("2026-05-29T00:00:00Z"), null, true);

        mockMvc.perform(get("/internal/v1/limit-management/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ruleId").value(RULE_ID.toString()))
                .andExpect(jsonPath("$.data[0].ownerType").value("MERCHANT"))
                .andExpect(jsonPath("$.data[0].ownerId").value("502118"))
                .andExpect(jsonPath("$.data[0].limitMode").value("UNLIMITED"))
                .andExpect(jsonPath("$.data[0].limitValue").value(nullValue()))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void createsAssignment() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ruleId": "0db59f6a-7f8c-45d6-b6a7-cc1fcb397c6e",
                                  "ownerType": "MERCHANT_GROUP",
                                  "ownerId": "4bb2ec8a-0cbb-4a42-8e62-7d4bd8567801",
                                  "limitMode": "LIMITED",
                                  "limitValue": "3000000.00",
                                  "validFrom": "2026-05-29T00:00:00Z",
                                  "validTo": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ruleId").value(RULE_ID.toString()))
                .andExpect(jsonPath("$.data.ownerType").value("MERCHANT_GROUP"))
                .andExpect(jsonPath("$.data.ownerId").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.data.limitMode").value("LIMITED"))
                .andExpect(jsonPath("$.data.limitValue").value("3000000.00"))
                .andExpect(jsonPath("$.data.validFrom").value("2026-05-29T00:00:00Z"))
                .andExpect(jsonPath("$.data.validTo").value(nullValue()))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void patchesAssignment() throws Exception {
        LimitAssignment assignment = repository.addAssignment(RULE_ID, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.LIMITED, "3000000.00",
                Instant.parse("2026-05-29T00:00:00Z"), null, true);

        mockMvc.perform(patch("/internal/v1/limit-management/assignments/{assignmentId}", assignment.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "limitMode": "BLOCKED",
                                  "limitValue": null,
                                  "validFrom": "2026-05-30T00:00:00Z",
                                  "validTo": null,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(assignment.id().toString()))
                .andExpect(jsonPath("$.data.ruleId").value(RULE_ID.toString()))
                .andExpect(jsonPath("$.data.ownerId").value("502118"))
                .andExpect(jsonPath("$.data.limitMode").value("BLOCKED"))
                .andExpect(jsonPath("$.data.limitValue").value(nullValue()))
                .andExpect(jsonPath("$.data.validFrom").value("2026-05-30T00:00:00Z"))
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void disablesAssignment() throws Exception {
        LimitAssignment assignment = repository.addAssignment(RULE_ID, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.UNLIMITED, null,
                Instant.parse("2026-05-29T00:00:00Z"), null, true);

        mockMvc.perform(post("/internal/v1/limit-management/assignments/{assignmentId}/disable", assignment.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(assignment.id().toString()))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void mapsAssignmentOverlapToConflictProblem() throws Exception {
        repository.addAssignment(RULE_ID, AssignmentOwnerType.MERCHANT, "502118",
                LimitMode.UNLIMITED, null,
                Instant.parse("2026-05-29T00:00:00Z"), null, true);

        mockMvc.perform(post("/internal/v1/limit-management/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ruleId": "0db59f6a-7f8c-45d6-b6a7-cc1fcb397c6e",
                                  "ownerType": "MERCHANT",
                                  "ownerId": "502118",
                                  "limitMode": "BLOCKED",
                                  "limitValue": null,
                                  "validFrom": "2026-05-30T00:00:00Z",
                                  "validTo": null
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_CONFLICT"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSupport {

        @Bean
        @Primary
        FakeRepository fakeLimitAssignmentRepository() {
            return new FakeRepository();
        }

        @Bean("testLimitAssignmentService")
        @Primary
        LimitAssignmentService limitAssignmentService(FakeRepository repository, java.time.Clock clock) {
            return new LimitAssignmentService(repository, clock);
        }
    }

    static class FakeRepository implements LimitAssignmentRepository {

        private final List<RuleReference> rules = new ArrayList<>();
        private final List<MerchantGroupReference> groups = new ArrayList<>();
        private final List<LimitAssignment> assignments = new ArrayList<>();

        FakeRepository clear() {
            rules.clear();
            groups.clear();
            assignments.clear();
            return this;
        }

        void addRule(UUID id, boolean active) {
            rules.add(new RuleReference(id, active));
        }

        void addMerchantGroup(UUID id, boolean enabled) {
            groups.add(new MerchantGroupReference(id, enabled));
        }

        LimitAssignment addAssignment(
                UUID ruleId,
                AssignmentOwnerType ownerType,
                String ownerId,
                LimitMode mode,
                String limitValue,
                Instant validFrom,
                Instant validTo,
                boolean enabled
        ) {
            LimitAssignment assignment = new LimitAssignment(
                    UUID.randomUUID(),
                    ruleId,
                    ownerType,
                    ownerId,
                    mode,
                    limitValue,
                    validFrom,
                    validTo,
                    enabled,
                    Instant.parse("2026-05-29T10:00:00Z"),
                    Instant.parse("2026-05-29T10:00:00Z")
            );
            assignments.add(assignment);
            return assignment;
        }

        @Override
        public List<LimitAssignment> listAssignments() {
            return List.copyOf(assignments);
        }

        @Override
        public Optional<LimitAssignment> findAssignment(UUID assignmentId) {
            return assignments.stream().filter(assignment -> assignment.id().equals(assignmentId)).findFirst();
        }

        @Override
        public Optional<RuleReference> findRule(UUID ruleId) {
            return rules.stream().filter(rule -> rule.id().equals(ruleId)).findFirst();
        }

        @Override
        public Optional<MerchantGroupReference> findMerchantGroup(UUID groupId) {
            return groups.stream().filter(group -> group.id().equals(groupId)).findFirst();
        }

        @Override
        public boolean hasEnabledOverlap(
                UUID excludedAssignmentId,
                UUID ruleId,
                AssignmentOwnerType ownerType,
                String ownerId,
                Instant validFrom,
                Instant validTo
        ) {
            return assignments.stream()
                    .filter(LimitAssignment::enabled)
                    .filter(assignment -> excludedAssignmentId == null || !assignment.id().equals(excludedAssignmentId))
                    .filter(assignment -> assignment.ruleId().equals(ruleId))
                    .filter(assignment -> assignment.ownerType() == ownerType)
                    .filter(assignment -> assignment.ownerId().equals(ownerId))
                    .anyMatch(assignment -> overlaps(validFrom, validTo, assignment.validFrom(), assignment.validTo()));
        }

        @Override
        public LimitAssignment saveAssignment(LimitAssignment assignment) {
            assignments.add(assignment);
            return assignment;
        }

        @Override
        public LimitAssignment updateAssignment(LimitAssignment assignment) {
            assignments.replaceAll(existing -> existing.id().equals(assignment.id()) ? assignment : existing);
            return assignment;
        }

        private boolean overlaps(Instant leftFrom, Instant leftTo, Instant rightFrom, Instant rightTo) {
            boolean leftStartsBeforeRightEnds = rightTo == null || leftFrom.isBefore(rightTo);
            boolean rightStartsBeforeLeftEnds = leftTo == null || rightFrom.isBefore(leftTo);
            return leftStartsBeforeRightEnds && rightStartsBeforeLeftEnds;
        }
    }
}
