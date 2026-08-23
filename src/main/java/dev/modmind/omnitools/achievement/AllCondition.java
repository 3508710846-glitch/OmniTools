package dev.modmind.omnitools.achievement;

import java.util.List;

public record AllCondition(List<AchievementCondition> children) implements AchievementCondition {
    public AllCondition {
        children = List.copyOf(children);
        if (children.isEmpty()) {
            throw new IllegalArgumentException("all condition must contain at least one child");
        }
    }

    @Override
    public boolean evaluate(StatisticEvaluationContext context) {
        return progress(context).completed();
    }

    @Override
    public ConditionProgress progress(StatisticEvaluationContext context) {
        List<ConditionProgress> progress = children.stream().map(child -> child.progress(context)).toList();
        long completed = progress.stream().filter(ConditionProgress::completed).count();
        return ConditionProgress.group(completed == progress.size(), completed, progress.size(), progress);
    }
}
