package ru.copperside.paylimits.management;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
class PayLimitManagementLocalProfileApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void localProfileStartsWithoutExternalConfigSources() {
        assertThat(environment.getActiveProfiles()).contains("local");
        assertThat(environment.getProperty("pay.environment")).isEqualTo("local");
        assertThat(environment.getProperty("spring.cloud.config.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.cloud.vault.enabled")).isEqualTo("false");
    }
}
