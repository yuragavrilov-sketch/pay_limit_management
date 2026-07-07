package ru.copperside.paylimits.management.audit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Adds a default {@code X-Operator-Id}/{@code X-Operator-Name} header to every MockMvc request so
 * existing controller/integration suites keep passing now that {@code OperatorHeaderInterceptor}
 * requires an operator identity on mutating requests. The default request's method/URL are ignored
 * by the merge; only the headers are inherited (and per-request headers still win).
 */
@TestConfiguration(proxyBeanMethods = false)
public class OperatorHeaderTestConfig {

    public static final String OPERATOR_ID = "op-test-001";
    public static final String OPERATOR_NAME = "Test Operator";

    @Bean
    MockMvcBuilderCustomizer operatorHeaderCustomizer() {
        return builder -> builder.defaultRequest(
                MockMvcRequestBuilders.get("/")
                        .header("X-Operator-Id", OPERATOR_ID)
                        .header("X-Operator-Name", OPERATOR_NAME));
    }
}
