package ru.copperside.paylimits.management.runtimeconfig.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.runtimeconfig.adapter.metrics.RuntimeManifestMetrics;
import ru.copperside.paylimits.management.runtimeconfig.application.port.out.RuntimeManifestRepository;

import java.time.Clock;

/**
 * Wires the manifest-size/manifest-age gauges (spec §7) to the actuator {@link MeterRegistry}.
 * Always registers the bean — the {@link RuntimeManifestRepository} is resolved lazily via
 * {@link ObjectProvider} on every scrape (see {@link RuntimeManifestMetrics}), so slices that
 * exclude the datasource, or that swap in a test repository AFTER this configuration class is
 * processed, still start cleanly and simply report 0 until a repository is available.
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeManifestMetricsConfig {

    @Bean
    RuntimeManifestMetrics runtimeManifestMetrics(
            ObjectProvider<RuntimeManifestRepository> repositoryProvider,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        return new RuntimeManifestMetrics(repositoryProvider, clock, meterRegistry);
    }
}
