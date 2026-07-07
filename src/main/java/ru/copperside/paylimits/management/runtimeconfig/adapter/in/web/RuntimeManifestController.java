package ru.copperside.paylimits.management.runtimeconfig.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.copperside.paylimits.management.common.web.ApiResponse;
import ru.copperside.paylimits.management.runtimeconfig.application.RuntimeManifestCompiler;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifest;

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
    public ResponseEntity<ApiResponse<RuntimeManifestResponse>> getActiveManifest(
            @RequestParam Instant at,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        return manifestResponse(compiler().getEffective(at), ifNoneMatch);
    }

    @GetMapping("/effective")
    public ResponseEntity<ApiResponse<RuntimeManifestResponse>> getEffectiveManifest(
            @RequestParam Instant at,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        return manifestResponse(compiler().getEffective(at), ifNoneMatch);
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
    public ResponseEntity<ApiResponse<RuntimeManifestResponse>> getManifest(
            @PathVariable UUID manifestId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        return manifestResponse(compiler().getManifest(manifestId), ifNoneMatch);
    }

    @PostMapping("/{manifestId}/rollback")
    public ApiResponse<RuntimeManifestResponse> rollbackManifest(
            @PathVariable UUID manifestId,
            @Valid @RequestBody RollbackRuntimeManifestRequest request
    ) {
        return ApiResponse.success(RuntimeManifestResponse.from(compiler().rollback(manifestId, request.effectiveFrom())), clock);
    }

    private ResponseEntity<ApiResponse<RuntimeManifestResponse>> manifestResponse(
            RuntimeManifest manifest,
            String ifNoneMatch
    ) {
        String checksum = manifest.checksum();
        if (checksum.equals(normalizeETag(ifNoneMatch))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(quote(checksum)).build();
        }
        return ResponseEntity.ok().eTag(quote(checksum))
                .body(ApiResponse.success(RuntimeManifestResponse.from(manifest), clock));
    }

    private static String normalizeETag(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2);
        }
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
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
