package ru.copperside.paylimits.management.audit.adapter.in.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.copperside.paylimits.management.audit.application.AuditEventService;
import ru.copperside.paylimits.management.common.web.ApiResponse;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Read-only audit query endpoint. Does not require an operator identity.
 */
@RestController
@RequestMapping("/internal/v1/limit-management")
public class AuditEventController {

    // Own mapper (only used to re-parse stored JSON text into a tree); the app has no shared
    // ObjectMapper bean, mirroring how the manifest repository builds its own JsonMapper.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final ObjectProvider<AuditEventService> serviceProvider;
    private final Clock clock;

    public AuditEventController(
            ObjectProvider<AuditEventService> serviceProvider,
            Clock clock
    ) {
        this.serviceProvider = serviceProvider;
        this.clock = clock;
    }

    @GetMapping("/audit-events")
    public ApiResponse<List<AuditEventResponse>> listAuditEvents(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        List<AuditEventResponse> events = service().find(entityType, entityId, from, to, page, size).stream()
                .map(event -> AuditEventResponse.from(event, objectMapper))
                .toList();
        return ApiResponse.success(events, clock);
    }

    private AuditEventService service() {
        return serviceProvider.getIfAvailable(() -> {
            throw new IllegalStateException("Audit event service is unavailable");
        });
    }
}
