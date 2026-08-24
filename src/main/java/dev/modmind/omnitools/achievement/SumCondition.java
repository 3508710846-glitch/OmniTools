package dev.modmind.omnitools.achievement;

import dev.modmind.omnitools.AchievementConfig;

import java.util.List;

/** A condition that adds several statistic sources before comparing one threshold. */
public record SumCondition(List<AchievementConfig.Requirement> requirements,
                           long atLeast, StatisticUnit unit, long progressDivisor,
                           String progressUnit) implements AchievementCondition {
    public SumCondition(List<AchievementConfig.Requirement> requirements, long atLeast) {
        this(requirements, atLeast, StatisticUnit.COUNT, 1L, "");
    }

    public SumCondition(List<AchievementConfig.Requirement> requirements, long atLeast, StatisticUnit unit) {
        this(requirements, atLeast, unit, 1L, "");
    }

    public SumCondition {
        requirements = List.copyOf(requirements);
        progressUnit = progressUnit == null ? "" : progressUnit;
        if (requirements.isEmpty() || atLeast < 1L || unit == null || progressDivisor < 1L) {
            throw new IllegalArgumentException("A sum condition needs sources, a positive threshold and a unit");
        }
    }

    @Override
    public boolean evaluate(StatisticEvaluationContext context) {
        return progress(context).completed();
    }

    @Override
    public ConditionProgress progress(StatisticEvaluationContext context) {
        long total = 0L;
        for (AchievementConfig.Requirement requirement : requirements) {
            total = Math.addExact(total, context.value(requirement));
        }
        return ConditionProgress.leaf(total, atLeast, total >= atLeast);
    }
}
