package ru.copperside.paylimits.management.runtimeconfig.adapter.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.runtimeconfig.application.port.out.RuntimeManifestRepository;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestSizeSnapshot;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeOperationType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring-free unit test for the manifest-size and manifest-age gauges (spec §7). Registers
 * {@link RuntimeManifestMetrics} against a plain {@link SimpleMeterRegistry} and a hand-rolled
 * {@link RuntimeManifestRepository} fake so gauge values can be read back deterministically.
 */
class RuntimeManifestMetricsTest {

    private static final Instant MANIFEST_CREATED_AT = Instant.parse("2026-05-29T10:00:00Z");

    @Test
    void sizeGaugesReportZeroWhenNoManifestHasBeenCompiledYet() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StubRepository repository = new StubRepository(Optional.empty(), Optional.empty());
        // Gauge.builder holds only a WEAK reference to the state object (RuntimeManifestMetrics), so
        // it must stay reachable via a local variable through the assertions below, or the instance is
        // GC-eligible immediately and the gauge could read back NaN.
        RuntimeManifestMetrics metrics = new RuntimeManifestMetrics(provider(repository), fixedClock(MANIFEST_CREATED_AT), registry);

        assertThat(sizeGauge(registry, "rules")).isZero();
        assertThat(sizeGauge(registry, "assignments")).isZero();
        assertThat(sizeGauge(registry, "memberships")).isZero();
        assertThat(sizeGauge(registry, "total")).isZero();
        assertThat(ageGauge(registry)).isZero();
    }

    @Test
    void sizeGaugesReflectTheLatestManifestSnapshot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeManifestSizeSnapshot snapshot = new RuntimeManifestSizeSnapshot(3, 5, 7, MANIFEST_CREATED_AT);
        StubRepository repository = new StubRepository(Optional.of(snapshot), Optional.empty());
        RuntimeManifestMetrics metrics = new RuntimeManifestMetrics(provider(repository), fixedClock(MANIFEST_CREATED_AT), registry);

        assertThat(sizeGauge(registry, "rules")).isEqualTo(3.0);
        assertThat(sizeGauge(registry, "assignments")).isEqualTo(5.0);
        assertThat(sizeGauge(registry, "memberships")).isEqualTo(7.0);
        assertThat(sizeGauge(registry, "total")).isEqualTo(15.0);
    }

    @Test
    void ageGaugeIsZeroWhenNoConfigChangePostdatesTheLatestManifest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeManifestSizeSnapshot snapshot = new RuntimeManifestSizeSnapshot(1, 1, 1, MANIFEST_CREATED_AT);
        // Latest config change happened BEFORE the manifest was compiled -> already published.
        StubRepository repository = new StubRepository(
                Optional.of(snapshot), Optional.of(MANIFEST_CREATED_AT.minusSeconds(30)));
        RuntimeManifestMetrics metrics = new RuntimeManifestMetrics(
                provider(repository), fixedClock(MANIFEST_CREATED_AT.plusSeconds(600)), registry);

        assertThat(ageGauge(registry)).isZero();
    }

    @Test
    void ageGaugeGrowsWhileAConfigChangePostdatesTheLatestManifest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeManifestSizeSnapshot snapshot = new RuntimeManifestSizeSnapshot(1, 1, 1, MANIFEST_CREATED_AT);
        Instant configChangedAt = MANIFEST_CREATED_AT.plusSeconds(120);
        Instant now = configChangedAt.plusSeconds(300);
        StubRepository repository = new StubRepository(Optional.of(snapshot), Optional.of(configChangedAt));
        RuntimeManifestMetrics metrics = new RuntimeManifestMetrics(provider(repository), fixedClock(now), registry);

        assertThat(ageGauge(registry)).isEqualTo(300.0);
    }

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static ObjectProvider<RuntimeManifestRepository> provider(RuntimeManifestRepository repository) {
        return new ObjectProvider<>() {
            @Override
            public RuntimeManifestRepository getObject() throws BeansException {
                if (repository == null) {
                    throw new NoSuchBeanDefinitionException(RuntimeManifestRepository.class);
                }
                return repository;
            }

            @Override
            public RuntimeManifestRepository getObject(Object... args) throws BeansException {
                return getObject();
            }

            @Override
            public RuntimeManifestRepository getIfAvailable() throws BeansException {
                return repository;
            }

            @Override
            public RuntimeManifestRepository getIfUnique() throws BeansException {
                return repository;
            }
        };
    }

    private static double sizeGauge(SimpleMeterRegistry registry, String component) {
        return registry.get(RuntimeManifestMetrics.MANIFEST_SIZE_METRIC)
                .tag("component", component)
                .gauge()
                .value();
    }

    private static double ageGauge(SimpleMeterRegistry registry) {
        return registry.get(RuntimeManifestMetrics.MANIFEST_UNPUBLISHED_CHANGE_AGE_METRIC)
                .gauge()
                .value();
    }

    /** Minimal {@link RuntimeManifestRepository} fake — only the two metrics-relevant methods matter here. */
    private static final class StubRepository implements RuntimeManifestRepository {

        private final Optional<RuntimeManifestSizeSnapshot> sizeSnapshot;
        private final Optional<Instant> latestConfigChangeAt;

        private StubRepository(Optional<RuntimeManifestSizeSnapshot> sizeSnapshot, Optional<Instant> latestConfigChangeAt) {
            this.sizeSnapshot = sizeSnapshot;
            this.latestConfigChangeAt = latestConfigChangeAt;
        }

        @Override
        public Optional<RuntimeManifestSizeSnapshot> findLatestManifestSize() {
            return sizeSnapshot;
        }

        @Override
        public Optional<Instant> findLatestConfigChangeAt() {
            return latestConfigChangeAt;
        }

        @Override
        public List<LimitRule> listActiveRulesForCompilation() {
            throw new UnsupportedOperationException("not used by metrics");
        }

        @Override
        public List<RuntimeCompiledAssignment> listEnabledAssignmentsForCompilation() {
            throw new UnsupportedOperationException("not used by metrics");
        }

        @Override
        public List<RuntimeMerchantGroupMembership> listMembershipsForCompilation() {
            throw new UnsupportedOperationException("not used by metrics");
        }

        @Override
        public List<RuntimeOperationType> listOperationTypesForManifest() {
            throw new UnsupportedOperationException("not used by metrics");
        }

        @Override
        public RuntimeManifest saveCompiledManifest(CompiledRuntimeManifestFactory factory) {
            throw new UnsupportedOperationException("not used by metrics");
        }

        @Override
        public Optional<RuntimeManifest> findManifest(UUID id) {
            throw new UnsupportedOperationException("not used by metrics");
        }

        @Override
        public Optional<RuntimeManifest> findEffectiveManifest(Instant at) {
            throw new UnsupportedOperationException("not used by metrics");
        }

        @Override
        public List<RuntimeManifestDescriptor> listScheduledManifests(Instant after, int limit) {
            throw new UnsupportedOperationException("not used by metrics");
        }

        @Override
        public List<RuntimeManifestDescriptor> listManifests(int limit) {
            throw new UnsupportedOperationException("not used by metrics");
        }
    }
}
