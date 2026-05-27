package ru.copperside.paylimits.management.merchantgroup.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.copperside.paylimits.management.common.web.ApiResponse;
import ru.copperside.paylimits.management.merchantgroup.application.AssignMembershipCommand;
import ru.copperside.paylimits.management.merchantgroup.application.CloseMembershipCommand;
import ru.copperside.paylimits.management.merchantgroup.application.CreateGroupTypeCommand;
import ru.copperside.paylimits.management.merchantgroup.application.CreateGroupCommand;
import ru.copperside.paylimits.management.merchantgroup.application.MerchantGroupService;
import ru.copperside.paylimits.management.merchantgroup.application.MembershipQuery;
import ru.copperside.paylimits.management.merchantgroup.application.PatchGroupCommand;
import ru.copperside.paylimits.management.merchantgroup.application.PatchGroupTypeCommand;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/limit-management")
public class MerchantGroupController {

    private static final String INTERNAL_ACTOR = "payadmin-bff";

    private final ObjectProvider<MerchantGroupService> serviceProvider;
    private final Clock clock;

    public MerchantGroupController(ObjectProvider<MerchantGroupService> serviceProvider, Clock clock) {
        this.serviceProvider = serviceProvider;
        this.clock = clock;
    }

    @PostMapping("/merchant-group-types")
    public ApiResponse<MerchantGroupTypeResponse> createGroupType(@Valid @RequestBody CreateGroupTypeRequest request) {
        var type = service().createType(new CreateGroupTypeCommand(
                request.code(),
                request.name(),
                request.description(),
                request.sortOrder()
        ));
        return ApiResponse.success(MerchantGroupTypeResponse.from(type), clock);
    }

    @GetMapping("/merchant-group-types")
    public ApiResponse<List<MerchantGroupTypeResponse>> listGroupTypes() {
        List<MerchantGroupTypeResponse> types = service().listTypes().stream()
                .map(MerchantGroupTypeResponse::from)
                .toList();
        return ApiResponse.success(types, clock);
    }

    @PatchMapping("/merchant-group-types/{typeId}")
    public ApiResponse<MerchantGroupTypeResponse> patchGroupType(
            @PathVariable UUID typeId,
            @Valid @RequestBody PatchGroupTypeRequest request
    ) {
        var type = service().updateType(typeId, new PatchGroupTypeCommand(
                request.name(),
                request.description(),
                request.enabled(),
                request.sortOrder()
        ));
        return ApiResponse.success(MerchantGroupTypeResponse.from(type), clock);
    }

    @GetMapping("/merchant-groups")
    public ApiResponse<List<MerchantGroupResponse>> listGroups(@RequestParam(required = false) UUID typeId) {
        List<MerchantGroupResponse> groups = service().listGroups(typeId).stream()
                .map(MerchantGroupResponse::from)
                .toList();
        return ApiResponse.success(groups, clock);
    }

    @PostMapping("/merchant-groups")
    public ApiResponse<MerchantGroupResponse> createGroup(@Valid @RequestBody CreateGroupRequest request) {
        var group = service().createGroup(new CreateGroupCommand(
                request.typeId(),
                request.code(),
                request.name(),
                request.description()
        ));
        return ApiResponse.success(MerchantGroupResponse.from(group), clock);
    }

    @PatchMapping("/merchant-groups/{groupId}")
    public ApiResponse<MerchantGroupResponse> patchGroup(
            @PathVariable UUID groupId,
            @Valid @RequestBody PatchGroupRequest request
    ) {
        var group = service().updateGroup(groupId, new PatchGroupCommand(
                request.name(),
                request.description(),
                request.enabled()
        ));
        return ApiResponse.success(MerchantGroupResponse.from(group), clock);
    }

    @GetMapping("/merchant-group-memberships")
    public ApiResponse<List<MerchantGroupMembershipResponse>> listMemberships(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(defaultValue = "current") String state
    ) {
        List<MerchantGroupMembershipResponse> memberships = service().listMemberships(
                        new MembershipQuery(merchantId, typeId, groupId, state))
                .stream()
                .map(MerchantGroupMembershipResponse::from)
                .toList();
        return ApiResponse.success(memberships, clock);
    }

    @PostMapping("/merchant-group-memberships")
    public ApiResponse<MerchantGroupMembershipResponse> assignMembership(@Valid @RequestBody AssignMembershipRequest request) {
        var membership = service().assignMembership(new AssignMembershipCommand(
                request.merchantId(),
                request.groupId(),
                request.validFrom(),
                INTERNAL_ACTOR
        ));
        return ApiResponse.success(MerchantGroupMembershipResponse.from(membership), clock);
    }

    @PostMapping("/merchant-group-memberships/{membershipId}/close")
    public ApiResponse<MerchantGroupMembershipResponse> closeMembership(
            @PathVariable UUID membershipId,
            @Valid @RequestBody CloseMembershipRequest request
    ) {
        var membership = service().closeMembership(new CloseMembershipCommand(
                membershipId,
                request.validTo(),
                INTERNAL_ACTOR
        ));
        return ApiResponse.success(MerchantGroupMembershipResponse.from(membership), clock);
    }

    private MerchantGroupService service() {
        return serviceProvider.getIfAvailable(() -> {
            throw new IllegalStateException("Merchant group service is unavailable");
        });
    }

    public record PatchGroupTypeRequest(String name, String description, Boolean enabled, Integer sortOrder) {
    }

    public record CreateGroupRequest(
            @NotNull UUID typeId,
            @jakarta.validation.constraints.NotBlank String code,
            @jakarta.validation.constraints.NotBlank String name,
            String description
    ) {
    }

    public record PatchGroupRequest(String name, String description, Boolean enabled) {
    }

    public record AssignMembershipRequest(
            @jakarta.validation.constraints.NotBlank String merchantId,
            @NotNull UUID groupId,
            @NotNull Instant validFrom
    ) {
    }

    public record CloseMembershipRequest(@NotNull Instant validTo) {
    }
}
