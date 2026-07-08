package ru.copperside.paylimits.management.common.application;

import ru.copperside.paylimits.management.limitrule.domain.LimitRule;

import java.util.List;

/**
 * Shared one-line helper for the checksum-sensitive {@code operationTypes} sort performed
 * identically by {@code RuleManifestCompiler.compileRule} and
 * {@code RuntimeManifestCompiler.compileRule} when building a rule's matcher. Extracted verbatim
 * -- do not change the sort (natural {@link String} order) or the return type (an immutable
 * {@link List} via {@code Stream.toList()}); both compiled manifest checksums depend on this
 * exact output.
 */
public final class OperationTypeSorting {

    private OperationTypeSorting() {
    }

    public static List<String> sorted(LimitRule rule) {
        return rule.operationTypes().stream().sorted().toList();
    }
}
