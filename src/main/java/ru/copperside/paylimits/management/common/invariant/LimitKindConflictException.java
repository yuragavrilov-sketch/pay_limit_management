package ru.copperside.paylimits.management.common.invariant;

import java.util.List;

/**
 * Raised at any of the three limit-kind-invariant checkpoints (membership, assignment/activation,
 * manifest compilation) when a merchant would end up receiving the same {@link LimitKindView}
 * from more than one merchant group. Surfaced as HTTP 409 for the interactive checkpoints and
 * HTTP 422 when detected during manifest compilation (spec §3.4).
 */
public class LimitKindConflictException extends RuntimeException {

    private final List<LimitKindConflict> conflicts;
    private final boolean compilation;

    public LimitKindConflictException(List<LimitKindConflict> conflicts, boolean compilation) {
        super(buildMessage(conflicts, compilation));
        this.conflicts = List.copyOf(conflicts);
        this.compilation = compilation;
    }

    public List<LimitKindConflict> conflicts() {
        return conflicts;
    }

    public boolean compilation() {
        return compilation;
    }

    private static String buildMessage(List<LimitKindConflict> conflicts, boolean compilation) {
        String stage = compilation ? "manifest compilation" : "invariant check";
        return "Limit kind conflict detected during " + stage + " for " + conflicts.size() + " merchant(s)";
    }
}
