package ru.copperside.paylimits.management.common.application;

import java.time.Instant;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Shared, Spring-free request-validation helpers for application services. Extracted from
 * identical copies of {@code requireCommand}/{@code requireUuid}/{@code requireInstant}/
 * {@code requireEnum}/{@code requireText} previously duplicated in {@code LimitRuleService},
 * {@code MerchantGroupService} and {@code LimitAssignmentService}.
 *
 * <p>Each caller supplies its own {@code ProblemException} factory (a
 * {@code BiFunction<code, message, exception>}, typically a {@code this::problem} method
 * reference) so the thrown exception type -- and therefore the module's error-DTO/HTTP mapping --
 * is unchanged by this extraction. The error codes/messages ({@code "VALIDATION_ERROR"},
 * {@code "<field> must not be null"} / {@code "must not be blank"}) are kept byte-for-byte
 * identical to the pre-refactor per-service copies.
 */
public final class RequestValidation {

    private RequestValidation() {
    }

    public static void requireCommand(Object command, BiFunction<String, String, ? extends RuntimeException> problem) {
        if (command == null) {
            throw problem.apply("VALIDATION_ERROR", "command must not be null");
        }
    }

    public static UUID requireUuid(UUID value, String field, BiFunction<String, String, ? extends RuntimeException> problem) {
        if (value == null) {
            throw problem.apply("VALIDATION_ERROR", field + " must not be null");
        }
        return value;
    }

    public static Instant requireInstant(Instant value, String field, BiFunction<String, String, ? extends RuntimeException> problem) {
        if (value == null) {
            throw problem.apply("VALIDATION_ERROR", field + " must not be null");
        }
        return value;
    }

    public static <T> T requireEnum(T value, String field, BiFunction<String, String, ? extends RuntimeException> problem) {
        if (value == null) {
            throw problem.apply("VALIDATION_ERROR", field + " must not be null");
        }
        return value;
    }

    public static String requireText(String value, String field, BiFunction<String, String, ? extends RuntimeException> problem) {
        if (value == null || value.isBlank()) {
            throw problem.apply("VALIDATION_ERROR", field + " must not be blank");
        }
        return value.trim();
    }
}
