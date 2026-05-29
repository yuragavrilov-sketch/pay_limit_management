package ru.copperside.paylimits.management.runtimeconfig.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "pay-limit-management.runtime-manifest")
public record RuntimeManifestProperties(
        @NotNull Duration minActivationLeadTime
) {
}
