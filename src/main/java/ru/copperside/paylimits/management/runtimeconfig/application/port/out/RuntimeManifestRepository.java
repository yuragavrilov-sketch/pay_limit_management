package ru.copperside.paylimits.management.runtimeconfig.application.port.out;

import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestSizeSnapshot;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeOperationType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuntimeManifestRepository {

    List<LimitRule> listActiveRulesForCompilation();

    List<RuntimeCompiledAssignment> listEnabledAssignmentsForCompilation();

    List<RuntimeMerchantGroupMembership> listMembershipsForCompilation();

    List<RuntimeOperationType> listOperationTypesForManifest();

    RuntimeManifest saveCompiledManifest(CompiledRuntimeManifestFactory factory);

    Optional<RuntimeManifest> findManifest(UUID id);

    Optional<RuntimeManifest> findEffectiveManifest(Instant at);

    List<RuntimeManifestDescriptor> listScheduledManifests(Instant after, int limit);

    List<RuntimeManifestDescriptor> listManifests(int limit);

    /**
     * Size (rule/assignment/membership counts) and creation time of the LATEST compiled manifest,
     * for the manifest-size and manifest-age gauges (spec §7). Default returns empty so existing
     * fakes/adapters that don't back a real manifest table are unaffected; a real implementation
     * (e.g. Postgres) overrides this with a cheap header-only query.
     */
    default Optional<RuntimeManifestSizeSnapshot> findLatestManifestSize() {
        return Optional.empty();
    }

    /**
     * Latest {@code updated_at} (or equivalent) across rules/assignments/memberships — the most
     * recent configuration change, used to compute the manifest-age "changes not published" gauge
     * (spec §7). Default returns empty (no known change) so existing fakes are unaffected.
     */
    default Optional<Instant> findLatestConfigChangeAt() {
        return Optional.empty();
    }

    @FunctionalInterface
    interface CompiledRuntimeManifestFactory {
        RuntimeManifest create(int version);
    }
}
