package ru.copperside.paylimits.management.runtimeconfig.application.port.out;

import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeCompiledAssignment;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestDescriptor;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeMerchantGroupMembership;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuntimeManifestRepository {

    List<LimitRule> listActiveRulesForCompilation();

    List<OperationType> listOperationTypesForCompilation();

    List<RuntimeCompiledAssignment> listEnabledAssignmentsForCompilation();

    List<RuntimeMerchantGroupMembership> listMembershipsForCompilation();

    RuntimeManifest saveCompiledManifest(CompiledRuntimeManifestFactory factory);

    Optional<RuntimeManifest> findManifest(UUID id);

    Optional<RuntimeManifest> findEffectiveManifest(Instant at);

    List<RuntimeManifestDescriptor> listScheduledManifests(Instant after, int limit);

    List<RuntimeManifestDescriptor> listManifests(int limit);

    @FunctionalInterface
    interface CompiledRuntimeManifestFactory {
        RuntimeManifest create(int version);
    }
}
