package ru.copperside.paylimits.management;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@OpenAPIDefinition(info = @Info(title = "pay-limit-management", version = "v1"))
@SpringBootApplication
@ConfigurationPropertiesScan
public class PayLimitManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayLimitManagementApplication.class, args);
    }
}
