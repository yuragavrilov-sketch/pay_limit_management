package ru.copperside.paylimits.management.runtimeconfig.domain.wire;

/**
 * Assignment owner in §4.3 form. {@code id} is {@code null} for {@code level = "GLOBAL"}, the group
 * UUID (as string) for {@code MERCHANT_GROUP}, and the merchant id for {@code MERCHANT}.
 */
public record OwnerV2(
        String level,
        String id
) {
}
