package ru.copperside.paylimits.management.limitrule.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import ru.copperside.paylimits.management.limitrule.application.CreateLimitRuleCommand;
import ru.copperside.paylimits.management.limitrule.application.CreateOperationTypeCommand;
import ru.copperside.paylimits.management.limitrule.application.LimitRuleService;
import ru.copperside.paylimits.management.limitrule.application.PatchLimitRuleCommand;
import ru.copperside.paylimits.management.limitrule.application.PatchOperationTypeCommand;
import ru.copperside.paylimits.management.limitrule.domain.AggregationScope;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.Measure;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/limit-management")
public class LimitRuleController {

    private final ObjectProvider<LimitRuleService> serviceProvider;
    private final Clock clock;

    public LimitRuleController(ObjectProvider<LimitRuleService> serviceProvider, Clock clock) {
        this.serviceProvider = serviceProvider;
        this.clock = clock;
    }

    @GetMapping("/operation-types")
    public ApiResponse<List<OperationTypeResponse>> listOperationTypes() {
        List<OperationTypeResponse> types = service().listOperationTypes().stream()
                .map(OperationTypeResponse::from)
                .toList();
        return ApiResponse.success(types, clock);
    }

    @GetMapping("/rule-dictionaries")
    public ApiResponse<RuleDictionaries> getRuleDictionaries() {
        return ApiResponse.success(service().getRuleDictionaries(), clock);
    }

    @GetMapping("/counterparty-types")
    public ApiResponse<List<CounterpartyTypeResponse>> listCounterpartyTypes() {
        return ApiResponse.success(CounterpartyTypeResponse.all(), clock);
    }

    @PostMapping("/operation-types")
    public ApiResponse<OperationTypeResponse> createOperationType(@Valid @RequestBody CreateOperationTypeRequest request) {
        var type = service().createOperationType(new CreateOperationTypeCommand(
                request.code(),
                request.name(),
                request.familyCode(),
                request.direction(),
                request.counterpartyType()
        ));
        return ApiResponse.success(OperationTypeResponse.from(type), clock);
    }

    @PatchMapping("/operation-types/{typeId}")
    public ApiResponse<OperationTypeResponse> patchOperationType(
            @PathVariable UUID typeId,
            @Valid @RequestBody PatchOperationTypeRequest request
    ) {
        var type = service().patchOperationType(typeId, new PatchOperationTypeCommand(
                request.name(),
                request.familyCode(),
                request.direction(),
                request.counterpartyType(),
                request.enabled()
        ));
        return ApiResponse.success(OperationTypeResponse.from(type), clock);
    }

    @GetMapping("/rules")
    public ApiResponse<List<LimitRuleResponse>> listRules() {
        List<LimitRuleResponse> rules = service().listRules().stream()
                .map(LimitRuleResponse::from)
                .toList();
        return ApiResponse.success(rules, clock);
    }

    @GetMapping("/rules/{ruleId}")
    public ApiResponse<LimitRuleResponse> getRule(@PathVariable UUID ruleId) {
        return ApiResponse.success(LimitRuleResponse.from(service().getRule(ruleId)), clock);
    }

    @PostMapping("/rules")
    public ApiResponse<LimitRuleResponse> createRule(@Valid @RequestBody CreateRuleRequest request) {
        var rule = service().createRule(new CreateLimitRuleCommand(
                request.code(),
                request.name(),
                request.operationTypes(),
                request.direction(),
                request.measure().toDomain(),
                request.limitTargetType(),
                request.limitValue(),
                request.errorMessageTemplate(),
                request.attributeSelector() == null
                        ? new RuleSelector<>(AttributeSelectorType.NONE, null)
                        : request.attributeSelector().toDomain()
        ));
        return ApiResponse.success(LimitRuleResponse.from(rule), clock);
    }

    @PatchMapping("/rules/{ruleId}")
    public ApiResponse<LimitRuleResponse> patchRule(
            @PathVariable UUID ruleId,
            @Valid @RequestBody PatchRuleRequest request
    ) {
        var rule = service().patchRule(ruleId, new PatchLimitRuleCommand(
                request.name(),
                request.operationTypes(),
                request.direction(),
                request.measure() == null ? null : request.measure().toDomain(),
                request.limitTargetType(),
                request.limitValue(),
                request.errorMessageTemplate(),
                request.attributeSelector() == null ? null : request.attributeSelector().toDomain()
        ));
        return ApiResponse.success(LimitRuleResponse.from(rule), clock);
    }

    @PostMapping("/rules/{ruleId}/activate")
    public ApiResponse<LimitRuleResponse> activateRule(@PathVariable UUID ruleId) {
        return ApiResponse.success(LimitRuleResponse.from(service().activateRule(ruleId)), clock);
    }

    @PostMapping("/rules/{ruleId}/disable")
    public ApiResponse<LimitRuleResponse> disableRule(@PathVariable UUID ruleId) {
        return ApiResponse.success(LimitRuleResponse.from(service().disableRule(ruleId)), clock);
    }

    @PostMapping("/rules/{ruleId}/new-version")
    public ApiResponse<LimitRuleResponse> createNewVersion(@PathVariable UUID ruleId) {
        return ApiResponse.success(LimitRuleResponse.from(service().createNewVersion(ruleId)), clock);
    }

    private LimitRuleService service() {
        return serviceProvider.getIfAvailable(() -> {
            throw new IllegalStateException("Limit rule service is unavailable");
        });
    }

    public record CreateOperationTypeRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String familyCode,
            @NotNull OperationDirection direction,
            @NotNull CounterpartyType counterpartyType
    ) {
    }

    public record PatchOperationTypeRequest(
            String name,
            String familyCode,
            OperationDirection direction,
            CounterpartyType counterpartyType,
            Boolean enabled
    ) {
    }

    public record CreateRuleRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotEmpty Set<String> operationTypes,
            @NotNull OperationDirection direction,
            @Valid @NotNull MeasureRequest measure,
            LimitTargetType limitTargetType,
            BigDecimal limitValue,
            @NotBlank String errorMessageTemplate,
            @Valid AttributeSelectorRequest attributeSelector
    ) {
    }

    public record PatchRuleRequest(
            String name,
            Set<String> operationTypes,
            OperationDirection direction,
            @Valid MeasureRequest measure,
            LimitTargetType limitTargetType,
            BigDecimal limitValue,
            String errorMessageTemplate,
            @Valid AttributeSelectorRequest attributeSelector
    ) {
    }

    public record MeasureRequest(
            @NotNull RuleMetric metric,
            RulePeriod period,
            AggregationScope aggregationScope,
            String currency,
            Integer intervalMinutes
    ) {
        Measure toDomain() {
            return new Measure(metric, period, aggregationScope, currency, intervalMinutes);
        }
    }

    public record AttributeSelectorRequest(@NotNull AttributeSelectorType type, String value) {
        RuleSelector<AttributeSelectorType> toDomain() {
            return new RuleSelector<>(type, value);
        }
    }
}
