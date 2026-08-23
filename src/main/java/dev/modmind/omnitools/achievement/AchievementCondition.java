package dev.modmind.omnitools.achievement;

/** A validated, immutable achievement condition tree node. */
public interface AchievementCondition {
    boolean evaluate(StatisticEvaluationContext context);

    ConditionProgress progress(StatisticEvaluationContext context);
}
