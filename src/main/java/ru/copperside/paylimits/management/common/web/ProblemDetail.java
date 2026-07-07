package ru.copperside.paylimits.management.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import ru.copperside.paylimits.management.common.invariant.LimitKindConflict;

import java.util.List;

public record ProblemDetail(
        String type,
        String title,
        int status,
        String code,
        String message,
        Object details,
        String traceId,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<LimitKindConflict> conflicts
) {
}
