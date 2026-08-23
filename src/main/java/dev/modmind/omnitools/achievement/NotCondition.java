package dev.modmind.omnitools.achievement;

import java.util.List;

/** A logical condition that succeeds when its single child does not succeed. */
public record NotCondition(AchievementCondition child) implements AchievementCondition {
    public NotCondition {
        if (child == null) {
            throw new IllegalArgumentException("not condition must contain a child");
        }
    }

    @Override
    public boolean evaluate(StatisticEvaluationContext context) {
        return progress(context).completed();
    }

    @Override
    public ConditionProgress progress(StatisticEvaluationContext context) {
        ConditionProgress childProgress = child.progress(context);
        return ConditionProgress.group(!childProgress.completed(), childProgress.completed() ? 0L : 1L,
                1L, List.of(childProgress));
    }
}
