package dev.modmind.omnitools.achievement;

import java.util.List;

/** Immutable progress result for one condition node and its descendants. */
public record ConditionProgress(boolean completed, long current, long target,
                                List<ConditionProgress> children) {
    public ConditionProgress {
        if (current < 0L || target < 0L) {
            throw new IllegalArgumentException("Condition progress values cannot be negative");
        }
        children = List.copyOf(children == null ? List.of() : children);
    }

    public static ConditionProgress leaf(long current, long target, boolean completed) {
        return new ConditionProgress(completed, Math.max(0L, current),
                Math.max(0L, target), List.of());
    }

    public static ConditionProgress group(boolean completed, long current, long target,
                                          List<ConditionProgress> children) {
        return new ConditionProgress(completed, Math.max(0L, current), Math.max(0L, target), children);
    }
}
