package ru.copperside.paylimits.management.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Clock;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        T data,
        Object meta,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        Object error,
        Instant timestamp
) {
    public static <T> ApiResponse<T> success(T data, Clock clock) {
        return new ApiResponse<>(data, null, null, Instant.now(clock));
    }
}
