package ru.copperside.paylimits.management.effectivelimits.adapter.in.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.copperside.paylimits.management.common.web.ApiResponse;
import ru.copperside.paylimits.management.effectivelimits.application.EffectiveLimitsService;

import java.time.Clock;
import java.time.Instant;

/**
 * Read-only preview of a merchant's effective limits (spec §3.5). Computed from the current
 * management configuration only, never the engine's counters. Does not require an operator
 * identity (GET is exempt from {@code X-Operator-Id}, spec §6).
 */
@RestController
@RequestMapping("/internal/v1/limit-management/merchants")
public class EffectiveLimitsController {

    private final ObjectProvider<EffectiveLimitsService> serviceProvider;
    private final Clock clock;

    public EffectiveLimitsController(ObjectProvider<EffectiveLimitsService> serviceProvider, Clock clock) {
        this.serviceProvider = serviceProvider;
        this.clock = clock;
    }

    @GetMapping("/{merchantId}/effective-limits")
    public ApiResponse<EffectiveLimitsResponse> getEffectiveLimits(
            @PathVariable String merchantId,
            @RequestParam Instant at
    ) {
        return ApiResponse.success(EffectiveLimitsResponse.from(service().resolve(merchantId, at)), clock);
    }

    private EffectiveLimitsService service() {
        return serviceProvider.getIfAvailable(() -> {
            throw new IllegalStateException("Effective limits service is unavailable");
        });
    }
}
