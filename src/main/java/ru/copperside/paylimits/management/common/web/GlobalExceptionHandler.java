package ru.copperside.paylimits.management.common.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.copperside.paylimits.management.audit.domain.OperatorProblemException;
import ru.copperside.paylimits.management.common.invariant.LimitKindConflict;
import ru.copperside.paylimits.management.common.invariant.LimitKindConflictException;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignmentProblemException;
import ru.copperside.paylimits.management.limitrule.domain.LimitRuleProblemException;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifestProblemException;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupProblemException;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestProblemException;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TYPE_BASE = "https://contracts.newpay/errors/";

    /**
     * Counter: limit-kind/assignment invariant conflicts (spec §7), tagged {@code code}
     * ({@code LIMIT_KIND_CONFLICT}/{@code ASSIGNMENT_CONFLICT}) and {@code status} ({@code 409}/
     * {@code 422}) so the three interactive checkpoints, the compile-time 422, and assignment
     * overlaps are all distinguishable in one metric.
     */
    static final String CONFLICT_COUNTER_METRIC = "pay_limit_management.manifest.conflicts";

    private static final String ASSIGNMENT_CONFLICT_CODE = "ASSIGNMENT_CONFLICT";

    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(Clock clock, MeterRegistry meterRegistry) {
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemEnvelope> handleBodyValidation(MethodArgumentNotValidException ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", ex.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemEnvelope> handleConstraintValidation(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", ex.getMessage());
    }

    @ExceptionHandler(OperatorProblemException.class)
    ResponseEntity<ProblemEnvelope> handleOperatorProblem(OperatorProblemException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.code(), "Operator identity required", messageWithoutCode(ex));
    }

    @ExceptionHandler(MerchantGroupProblemException.class)
    ResponseEntity<ProblemEnvelope> handleDomainProblem(MerchantGroupProblemException ex) {
        HttpStatus status = switch (ex.code()) {
            case "GROUP_TYPE_NOT_FOUND", "GROUP_NOT_FOUND", "MEMBERSHIP_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VALIDATION_ERROR", "INVALID_MEMBERSHIP_PERIOD" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, ex.code(), "Merchant group problem", ex.getMessage());
    }

    @ExceptionHandler(LimitRuleProblemException.class)
    ResponseEntity<ProblemEnvelope> handleLimitRuleProblem(LimitRuleProblemException ex) {
        HttpStatus status = switch (ex.code()) {
            case "OPERATION_TYPE_NOT_FOUND", "RULE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_RULE_DEFINITION", "RULE_SELECTOR_INVALID", "VALIDATION_ERROR",
                 "OPERATION_TYPE_INVALID_DIRECTION", "OPERATION_TYPE_DISABLED" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, ex.code(), "Limit rule problem", ex.getMessage());
    }

    @ExceptionHandler(LimitAssignmentProblemException.class)
    ResponseEntity<ProblemEnvelope> handleLimitAssignmentProblem(LimitAssignmentProblemException ex) {
        HttpStatus status = switch (ex.code()) {
            case "ASSIGNMENT_NOT_FOUND", "RULE_NOT_FOUND", "GROUP_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VALIDATION_ERROR" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        if (ASSIGNMENT_CONFLICT_CODE.equals(ex.code())) {
            incrementConflictCounter(ASSIGNMENT_CONFLICT_CODE, status);
        }
        return problem(status, ex.code(), titleForAssignmentProblem(ex.code()), messageWithoutCode(ex), ex.details());
    }

    @ExceptionHandler(RuleManifestProblemException.class)
    ResponseEntity<ProblemEnvelope> handleRuleManifestProblem(RuleManifestProblemException ex) {
        HttpStatus status = switch (ex.code()) {
            case "RULE_MANIFEST_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VALIDATION_ERROR" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, ex.code(), titleForManifestProblem(ex.code()), messageWithoutCode(ex), ex.details());
    }

    @ExceptionHandler(RuntimeManifestProblemException.class)
    ResponseEntity<ProblemEnvelope> handleRuntimeManifestProblem(RuntimeManifestProblemException ex) {
        HttpStatus status = switch (ex.code()) {
            case "RUNTIME_MANIFEST_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VALIDATION_ERROR", "RUNTIME_MANIFEST_LEAD_TIME_VIOLATION" -> HttpStatus.BAD_REQUEST;
            // A rule that can't be compiled into a valid manifest is a compile-time config rejection,
            // consistent with the 422 returned for the compile-time limit-kind invariant conflict.
            case "RUNTIME_MANIFEST_INVALID_RULE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, ex.code(), titleForRuntimeManifestProblem(ex.code()), messageWithoutCode(ex), ex.details());
    }

    @ExceptionHandler(LimitKindConflictException.class)
    ResponseEntity<ProblemEnvelope> handleLimitKindConflict(LimitKindConflictException ex) {
        HttpStatus status = ex.compilation() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT;
        incrementConflictCounter("LIMIT_KIND_CONFLICT", status);
        return problem(status, "LIMIT_KIND_CONFLICT",
                "Merchant already receives a conflicting limit kind from another group",
                ex.getMessage(), null, ex.conflicts());
    }

    private void incrementConflictCounter(String code, HttpStatus status) {
        meterRegistry.counter(CONFLICT_COUNTER_METRIC, "code", code, "status", String.valueOf(status.value()))
                .increment();
    }

    private ResponseEntity<ProblemEnvelope> problem(HttpStatus status, String code, String title, String message) {
        return problem(status, code, title, message, null, null);
    }

    private ResponseEntity<ProblemEnvelope> problem(
            HttpStatus status,
            String code,
            String title,
            String message,
            Object details
    ) {
        return problem(status, code, title, message, details, null);
    }

    private ResponseEntity<ProblemEnvelope> problem(
            HttpStatus status,
            String code,
            String title,
            String message,
            Object details,
            List<LimitKindConflict> conflicts
    ) {
        ProblemDetail detail = new ProblemDetail(
                TYPE_BASE + code.toLowerCase().replace('_', '-'),
                title,
                status.value(),
                code,
                message,
                details,
                UUID.randomUUID().toString(),
                conflicts
        );
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemEnvelope.of(detail, clock));
    }

    private String titleForAssignmentProblem(String code) {
        return switch (code) {
            case "ASSIGNMENT_CONFLICT" -> "Assignment conflict";
            case "ASSIGNMENT_NOT_FOUND" -> "Assignment not found";
            default -> "Limit assignment problem";
        };
    }

    private String titleForManifestProblem(String code) {
        return switch (code) {
            case "RULE_MANIFEST_CONFLICT" -> "Rule manifest conflict";
            case "RULE_MANIFEST_NOT_FOUND" -> "Rule manifest not found";
            default -> "Rule manifest problem";
        };
    }

    private String titleForRuntimeManifestProblem(String code) {
        return switch (code) {
            case "RUNTIME_MANIFEST_CONFLICT" -> "Runtime manifest conflict";
            case "RUNTIME_MANIFEST_INVALID_RULE" -> "Runtime manifest invalid rule";
            case "RUNTIME_MANIFEST_LEAD_TIME_VIOLATION" -> "Runtime manifest lead time violation";
            case "RUNTIME_MANIFEST_NOT_FOUND" -> "Runtime manifest not found";
            default -> "Runtime manifest problem";
        };
    }

    private String messageWithoutCode(RuntimeException ex) {
        String message = ex.getMessage();
        int separator = message == null ? -1 : message.indexOf(": ");
        return separator < 0 ? message : message.substring(separator + 2);
    }
}
