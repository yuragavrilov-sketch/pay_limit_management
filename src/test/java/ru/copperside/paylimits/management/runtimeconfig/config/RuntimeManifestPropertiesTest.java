package ru.copperside.paylimits.management.runtimeconfig.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeManifestPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsMinActivationLeadTimeAndBusinessTimezone() {
        contextRunner
                .withPropertyValues(
                        "pay-limit-management.runtime-manifest.min-activation-lead-time=5m",
                        "pay-limit-management.runtime-manifest.business-timezone=Europe/Moscow")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RuntimeManifestProperties properties = context.getBean(RuntimeManifestProperties.class);
                    assertThat(properties.minActivationLeadTime()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.businessTimezone()).isEqualTo("Europe/Moscow");
                });
    }

    @Test
    void rejectsMissingMinActivationLeadTime() {
        contextRunner
                .withPropertyValues("pay-limit-management.runtime-manifest.business-timezone=Europe/Moscow")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMissingBusinessTimezone() {
        contextRunner
                .withPropertyValues("pay-limit-management.runtime-manifest.min-activation-lead-time=5m")
                .run(context -> assertThat(context).hasFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RuntimeManifestProperties.class)
    static class TestConfig {
    }
}
