package ru.copperside.paylimits.management;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
class PayLimitManagementApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void contextLoadsWithLocalProfile() {
    }

    @Test
    void applicationIdentityMatchesConfigStandard() {
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("pay-limit-management");
        assertThat(environment.getProperty("server.port")).isEqualTo("8084");
        assertThat(PayLimitManagementApplication.class.getPackageName())
                .isEqualTo("ru.copperside.paylimits.management");
    }
}
