package ru.copperside.paylimits.management.runtimeconfig.domain.wire;

/**
 * Attribute selector — extension beyond §4.3. Always present; {@code type = "NONE"} with
 * {@code value = null} when the rule is not narrowed by an attribute.
 */
public record AttributeSelectorV2(
        String type,
        String value
) {
}
