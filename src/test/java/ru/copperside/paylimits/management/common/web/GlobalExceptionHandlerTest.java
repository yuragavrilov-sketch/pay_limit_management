package ru.copperside.paylimits.management.common.web;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.copperside.paylimits.management.common.invariant.LimitKindConflict;
import ru.copperside.paylimits.management.common.invariant.LimitKindConflictException;
import ru.copperside.paylimits.management.common.invariant.LimitKindView;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignmentProblemException;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(GlobalExceptionHandlerTest.TestSupport.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void mapsLimitKindConflictExceptionToConflictProblemWithConflictsBlock() throws Exception {
        mockMvc.perform(get("/test/limit-kind-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LIMIT_KIND_CONFLICT"))
                .andExpect(jsonPath("$.error.conflicts[0].merchantId").value("M42"))
                .andExpect(jsonPath("$.error.conflicts[0].limitKind.checkType").value("COUNT_DAY"))
                .andExpect(jsonPath("$.error.conflicts[0].limitKind.targetType").value("CARD"))
                .andExpect(jsonPath("$.error.conflicts[0].limitKind.direction").value("OUT"))
                .andExpect(jsonPath("$.error.conflicts[0].limitKind.operationTypes[0]").value("OCT"));
    }

    @Test
    void unrelatedNotFoundProblemHasNoConflictsField() throws Exception {
        mockMvc.perform(get("/test/limit-rule-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RULE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.conflicts").doesNotExist());
    }

    // Task 4 (M2): the 409 (interactive) LIMIT_KIND_CONFLICT increments the conflict counter tagged
    // code=LIMIT_KIND_CONFLICT,status=409 (spec §7).
    @Test
    void interactiveLimitKindConflictIncrementsConflictCounterTaggedStatus409() throws Exception {
        double before = conflictCounterCount("LIMIT_KIND_CONFLICT", "409");

        mockMvc.perform(get("/test/limit-kind-conflict"))
                .andExpect(status().isConflict());

        assertThat(conflictCounterCount("LIMIT_KIND_CONFLICT", "409")).isEqualTo(before + 1);
    }

    // Task 4 (M2): the 422 (compile-time) LIMIT_KIND_CONFLICT increments the SAME counter but tagged
    // status=422, so 409 vs 422 are distinguishable (spec §7).
    @Test
    void compileTimeLimitKindConflictIncrementsConflictCounterTaggedStatus422() throws Exception {
        double before = conflictCounterCount("LIMIT_KIND_CONFLICT", "422");

        mockMvc.perform(get("/test/limit-kind-conflict-compilation"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("LIMIT_KIND_CONFLICT"));

        assertThat(conflictCounterCount("LIMIT_KIND_CONFLICT", "422")).isEqualTo(before + 1);
    }

    // Task 4 (M2): ASSIGNMENT_CONFLICT (409) increments the counter tagged code=ASSIGNMENT_CONFLICT,
    // status=409, distinguishable from LIMIT_KIND_CONFLICT (spec §7).
    @Test
    void assignmentConflictIncrementsConflictCounterTaggedStatus409() throws Exception {
        double before = conflictCounterCount("ASSIGNMENT_CONFLICT", "409");

        mockMvc.perform(get("/test/assignment-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_CONFLICT"));

        assertThat(conflictCounterCount("ASSIGNMENT_CONFLICT", "409")).isEqualTo(before + 1);
    }

    private double conflictCounterCount(String code, String status) {
        io.micrometer.core.instrument.Counter counter = meterRegistry.find(GlobalExceptionHandler.CONFLICT_COUNTER_METRIC)
                .tag("code", code)
                .tag("status", status)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSupport {

        @Bean
        TestController limitKindConflictTestController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/test/limit-kind-conflict")
        void throwLimitKindConflict() {
            throw new LimitKindConflictException(List.of(sampleConflict()), false);
        }

        @GetMapping("/test/limit-kind-conflict-compilation")
        void throwLimitKindConflictAtCompilation() {
            throw new LimitKindConflictException(List.of(sampleConflict()), true);
        }

        @GetMapping("/test/assignment-conflict")
        void throwAssignmentConflict() {
            throw new LimitAssignmentProblemException("ASSIGNMENT_CONFLICT",
                    "Enabled assignments for the same rule and owner must not overlap");
        }

        @GetMapping("/test/limit-rule-not-found")
        void throwLimitRuleNotFound() {
            throw new LimitRuleProblemException("RULE_NOT_FOUND", "rule not found");
        }

        private static LimitKindConflict sampleConflict() {
            return new LimitKindConflict(
                    "M42",
                    new LimitKindView("COUNT_DAY", "CARD", "OUT", List.of("OCT")),
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    UUID.fromString("22222222-2222-2222-2222-222222222222"));
        }
    }
}
