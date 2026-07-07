package ru.copperside.paylimits.management.runtimeconfig.adapter.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import ru.copperside.paylimits.management.runtimeconfig.application.port.out.RuntimeManifestRepository;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestSizeSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Registers the manifest-size and manifest-age Micrometer gauges from spec §7. Adapter-layer only
 * (queries the Spring-free {@link RuntimeManifestRepository} port); the application layer stays
 * unaware Micrometer exists. Each gauge value is computed lazily via a supplier on every scrape, so
 * readings always reflect the live database state without any background polling loop.
 */
public class RuntimeManifestMetrics {

    /** Gauge: size of the LATEST compiled manifest, tagged by {@code component} (rules|assignments|memberships|total). */
    public static final String MANIFEST_SIZE_METRIC = "pay_limit_management.manifest.size";

    /**
     * Gauge: seconds elapsed since the latest configuration change (rules/assignments/memberships)
     * that postdates the latest compiled manifest — i.e. how long a compile has been "owed". Zero
     * whenever every change is already covered by the latest manifest. A monitoring rule alerting on
     * "changes not published" (spec §7) thresholds this value; the threshold itself lives outside
     * this codebase.
     */
    public static final String MANIFEST_UNPUBLISHED_CHANGE_AGE_METRIC =
            "pay_limit_management.manifest.unpublished_change_age_seconds";

    private final ObjectProvider<RuntimeManifestRepository> repositoryProvider;
    private final Clock clock;

    /**
     * Takes an {@link ObjectProvider} (not the port directly) so gauge registration doesn't depend on
     * {@code @ConditionalOnBean} evaluation order against test-only repository overrides — the
     * repository is resolved lazily on every scrape, exactly like {@code RuntimeManifestController}
     * resolves its compiler. Absent repository (e.g. a slice test excluding the datasource) yields 0,
     * never a startup failure.
     */
    public RuntimeManifestMetrics(ObjectProvider<RuntimeManifestRepository> repositoryProvider, Clock clock, MeterRegistry registry) {
        this.repositoryProvider = repositoryProvider;
        this.clock = clock;
        registerSizeGauge(registry, "rules", RuntimeManifestSizeSnapshot::ruleCount);
        registerSizeGauge(registry, "assignments", RuntimeManifestSizeSnapshot::assignmentCount);
        registerSizeGauge(registry, "memberships", RuntimeManifestSizeSnapshot::membershipCount);
        registerSizeGauge(registry, "total",
                snapshot -> snapshot.ruleCount() + snapshot.assignmentCount() + snapshot.membershipCount());
        Gauge.builder(MANIFEST_UNPUBLISHED_CHANGE_AGE_METRIC, this, RuntimeManifestMetrics::unpublishedChangeAgeSeconds)
                .description("Seconds since the latest config change not yet covered by a compiled manifest")
                .register(registry);
    }

    private void registerSizeGauge(MeterRegistry registry, String component, ToIntFunction<RuntimeManifestSizeSnapshot> extractor) {
        Gauge.builder(MANIFEST_SIZE_METRIC, this, self -> self.sizeValue(extractor))
                .tag("component", component)
                .description("Size of the latest compiled runtime manifest")
                .register(registry);
    }

    private double sizeValue(ToIntFunction<RuntimeManifestSizeSnapshot> extractor) {
        RuntimeManifestRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            return 0.0;
        }
        return repository.findLatestManifestSize().map(extractor::applyAsInt).orElse(0);
    }

    double unpublishedChangeAgeSeconds() {
        RuntimeManifestRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            return 0.0;
        }
        Optional<RuntimeManifestSizeSnapshot> latestManifest = repository.findLatestManifestSize();
        if (latestManifest.isEmpty()) {
            return 0.0;
        }
        Instant manifestCreatedAt = latestManifest.get().createdAt();
        Optional<Instant> latestChange = repository.findLatestConfigChangeAt();
        if (latestChange.isEmpty() || !latestChange.get().isAfter(manifestCreatedAt)) {
            return 0.0;
        }
        Duration sincePendingChange = Duration.between(latestChange.get(), Instant.now(clock));
        return sincePendingChange.isNegative() ? 0.0 : (double) sincePendingChange.getSeconds();
    }
}
