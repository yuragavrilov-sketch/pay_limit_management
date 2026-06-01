package ru.copperside.paylimits.management.runtimeconfig.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.copperside.paylimits.management.common.web.ApiResponse;
import ru.copperside.paylimits.management.runtimeconfig.application.RuntimeManifestCompiler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/limit-management/runtime-manifests")
public class RuntimeManifestController {

    private final ObjectProvider<RuntimeManifestCompiler> compilerProvider;
    private final Clock clock;

    public RuntimeManifestController(ObjectProvider<RuntimeManifestCompiler> compilerProvider, Clock clock) {
        this.compilerProvider = compilerProvider;
        this.clock = clock;
    }

    @PostMapping
    public ApiResponse<RuntimeManifestResponse> compileManifest(
            @Valid @RequestBody CompileRuntimeManifestRequest request
    ) {
        return ApiResponse.success(RuntimeManifestResponse.from(compiler().compile(request.effectiveFrom())), clock);
    }

    @GetMapping
    public ApiResponse<List<RuntimeManifestDescriptorResponse>> listManifestLifecycle(
            @RequestParam Instant at,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        List<RuntimeManifestDescriptorResponse> descriptors = compiler().listLifecycle(at, limit).stream()
                .map(RuntimeManifestDescriptorResponse::from)
                .toList();
        return ApiResponse.success(descriptors, clock);
    }

    @GetMapping("/active")
    public ApiResponse<RuntimeManifestResponse> getActiveManifest(@RequestParam Instant at) {
        return ApiResponse.success(RuntimeManifestResponse.from(compiler().getEffective(at)), clock);
    }

    @GetMapping("/effective")
    public ApiResponse<RuntimeManifestResponse> getEffectiveManifest(@RequestParam Instant at) {
        return ApiResponse.success(RuntimeManifestResponse.from(compiler().getEffective(at)), clock);
    }

    @GetMapping("/scheduled")
    public ApiResponse<List<RuntimeManifestScheduledDescriptorResponse>> listScheduledManifests(
            @RequestParam Instant after,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        List<RuntimeManifestScheduledDescriptorResponse> descriptors = compiler().listScheduled(after, limit).stream()
                .map(RuntimeManifestScheduledDescriptorResponse::from)
                .toList();
        return ApiResponse.success(descriptors, clock);
    }

    @GetMapping("/{manifestId}")
    public ApiResponse<RuntimeManifestResponse> getManifest(@PathVariable UUID manifestId) {
        return ApiResponse.success(RuntimeManifestResponse.from(compiler().getManifest(manifestId)), clock);
    }

    @PostMapping("/{manifestId}/rollback")
    public ApiResponse<RuntimeManifestResponse> rollbackManifest(
            @PathVariable UUID manifestId,
            @Valid @RequestBody RollbackRuntimeManifestRequest request
    ) {
        return ApiResponse.success(RuntimeManifestResponse.from(compiler().rollback(manifestId, request.effectiveFrom())), clock);
    }

    private RuntimeManifestCompiler compiler() {
        return compilerProvider.getIfAvailable(() -> {
            throw new IllegalStateException("Runtime manifest compiler is unavailable");
        });
    }

    public record CompileRuntimeManifestRequest(@NotNull Instant effectiveFrom) {
    }

    public record RollbackRuntimeManifestRequest(@NotNull Instant effectiveFrom) {
    }
}
