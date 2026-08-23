package dev.modmind.omnitools.achievement;

import java.util.List;

/** A logical condition that succeeds when at least one child succeeds. */
public record AnyCondition(List<AchievementCondition> children) implements AchievementCondition {
    public AnyCondition {
        children = List.copyOf(children);
        if (children.isEmpty()) {
            throw new IllegalArgumentException("any condition must contain at least one child");
        }
    }

    @Override
    public boolean evaluate(StatisticEvaluationContext context) {
        return progress(context).completed();
    }

    @Override
    public ConditionProgress progress(StatisticEvaluationContext context) {
        List<ConditionProgress> progress = children.stream().map(child -> child.progress(context)).toList();
        boolean completed = progress.stream().anyMatch(ConditionProgress::completed);
        return ConditionProgress.group(completed, completed ? 1L : 0L, 1L, progress);
    }
}
