package ru.copperside.paylimits.management.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class LimitManagementPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsServiceName() {
        contextRunner
                .withPropertyValues("pay-limit-management.service-name=Limit Management")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LimitManagementProperties.class).serviceName())
                            .isEqualTo("Limit Management");
                });
    }

    @Test
    void rejectsBlankServiceName() {
        contextRunner
                .withPropertyValues("pay-limit-management.service-name=")
                .run(context -> assertThat(context).hasFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LimitManagementProperties.class)
    static class TestConfig {
    }
}
