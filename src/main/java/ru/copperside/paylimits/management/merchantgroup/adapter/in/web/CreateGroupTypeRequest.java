package ru.copperside.paylimits.management.merchantgroup.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateGroupTypeRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        @NotNull Integer sortOrder
) {
}
