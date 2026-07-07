package ru.copperside.paylimits.management.limitassignment.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.copperside.paylimits.management.common.web.ApiResponse;
import ru.copperside.paylimits.management.limitassignment.application.CreateLimitAssignmentCommand;
import ru.copperside.paylimits.management.limitassignment.application.LimitAssignmentService;
import ru.copperside.paylimits.management.limitassignment.application.PatchLimitAssignmentCommand;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/limit-management")
public class LimitAssignmentController {

    private final ObjectProvider<LimitAssignmentService> serviceProvider;
    private final Clock clock;

    public LimitAssignmentController(ObjectProvider<LimitAssignmentService> serviceProvider, Clock clock) {
        this.serviceProvider = serviceProvider;
        this.clock = clock;
    }

    @GetMapping("/assignments")
    public ApiResponse<List<LimitAssignmentResponse>> listAssignments() {
        List<LimitAssignmentResponse> assignments = service().listAssignments().stream()
                .map(LimitAssignmentResponse::from)
                .toList();
        return ApiResponse.success(assignments, clock);
    }

    @PostMapping("/assignments")
    public ApiResponse<LimitAssignmentResponse> createAssignment(
            @Valid @RequestBody CreateAssignmentRequest request
    ) {
        var assignment = service().createAssignment(new CreateLimitAssignmentCommand(
                request.ruleId(),
                request.ownerType(),
                request.ownerId(),
                request.limitMode(),
                request.validFrom(),
                request.validTo()
        ));
        return ApiResponse.success(LimitAssignmentResponse.from(assignment), clock);
    }

    @PatchMapping("/assignments/{assignmentId}")
    public ApiResponse<LimitAssignmentResponse> patchAssignment(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody PatchAssignmentRequest request
    ) {
        var assignment = service().patchAssignment(assignmentId, new PatchLimitAssignmentCommand(
                request.limitMode(),
                request.validFrom(),
                request.validTo(),
                request.enabled()
        ));
        return ApiResponse.success(LimitAssignmentResponse.from(assignment), clock);
    }

    @PostMapping("/assignments/{assignmentId}/disable")
    public ApiResponse<LimitAssignmentResponse> disableAssignment(@PathVariable UUID assignmentId) {
        return ApiResponse.success(LimitAssignmentResponse.from(service().disableAssignment(assignmentId)), clock);
    }

    private LimitAssignmentService service() {
        return serviceProvider.getIfAvailable(() -> {
            throw new IllegalStateException("Limit assignment service is unavailable");
        });
    }

    public record CreateAssignmentRequest(
            @NotNull UUID ruleId,
            @NotNull AssignmentOwnerType ownerType,
            @NotBlank String ownerId,
            @NotNull LimitMode limitMode,
            @NotNull Instant validFrom,
            Instant validTo
    ) {
    }

    public record PatchAssignmentRequest(
            LimitMode limitMode,
            Instant validFrom,
            Instant validTo,
            Boolean enabled
    ) {
    }
}
