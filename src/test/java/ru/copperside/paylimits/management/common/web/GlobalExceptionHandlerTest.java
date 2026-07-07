package ru.copperside.paylimits.management.common.web;

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
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;

import java.util.List;
import java.util.UUID;

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
            LimitKindConflict conflict = new LimitKindConflict(
                    "M42",
                    new LimitKindView("COUNT_DAY", "CARD", "OUT", List.of("OCT")),
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    UUID.fromString("22222222-2222-2222-2222-222222222222"));
            throw new LimitKindConflictException(List.of(conflict), false);
        }

        @GetMapping("/test/limit-rule-not-found")
        void throwLimitRuleNotFound() {
            throw new LimitRuleProblemException("RULE_NOT_FOUND", "rule not found");
        }
    }
}
