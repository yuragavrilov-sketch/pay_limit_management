package ru.copperside.paylimits.management.limitrule.adapter.in.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.copperside.paylimits.management.common.web.ApiResponse;
import ru.copperside.paylimits.management.limitrule.application.RuleManifestCompiler;

import java.time.Clock;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/limit-management/rule-manifests")
public class RuleManifestController {

    private final ObjectProvider<RuleManifestCompiler> compilerProvider;
    private final Clock clock;

    public RuleManifestController(ObjectProvider<RuleManifestCompiler> compilerProvider, Clock clock) {
        this.compilerProvider = compilerProvider;
        this.clock = clock;
    }

    @PostMapping
    public ApiResponse<RuleManifestResponse> compileManifest() {
        return ApiResponse.success(RuleManifestResponse.from(compiler().compile()), clock);
    }

    @GetMapping("/latest")
    public ApiResponse<RuleManifestResponse> getLatestManifest() {
        return ApiResponse.success(RuleManifestResponse.from(compiler().getLatest()), clock);
    }

    @GetMapping("/{manifestId}")
    public ApiResponse<RuleManifestResponse> getManifest(@PathVariable UUID manifestId) {
        return ApiResponse.success(RuleManifestResponse.from(compiler().getManifest(manifestId)), clock);
    }

    private RuleManifestCompiler compiler() {
        return compilerProvider.getIfAvailable(() -> {
            throw new IllegalStateException("Rule manifest compiler is unavailable");
        });
    }
}
