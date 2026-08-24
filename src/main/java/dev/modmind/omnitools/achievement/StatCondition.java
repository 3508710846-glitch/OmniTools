package dev.modmind.omnitools.achievement;

import dev.modmind.omnitools.AchievementConfig;

import java.util.List;

/** A v2 stat leaf backed by resolved vanilla statistic targets. */
public record StatCondition(List<AchievementConfig.Requirement> requirements, long atLeast,
                            TargetMatch match, StatisticUnit unit, long progressDivisor,
                            String progressUnit) implements AchievementCondition {
    /** Backwards-compatible constructor for legacy callers: all targets are summed. */
    public StatCondition(List<AchievementConfig.Requirement> requirements, long atLeast) {
        this(requirements, atLeast, TargetMatch.SUM, StatisticUnit.COUNT, 1L, "");
    }

    public StatCondition(List<AchievementConfig.Requirement> requirements, long atLeast, TargetMatch match) {
        this(requirements, atLeast, match, StatisticUnit.COUNT, 1L, "");
    }

    public StatCondition(List<AchievementConfig.Requirement> requirements, long atLeast, TargetMatch match,
                         StatisticUnit unit) {
        this(requirements, atLeast, match, unit, 1L, "");
    }

    public StatCondition {
        requirements = List.copyOf(requirements);
        progressUnit = progressUnit == null ? "" : progressUnit;
        if (requirements.isEmpty() || atLeast < 1L || match == null || unit == null || progressDivisor < 1L) {
            throw new IllegalArgumentException("A stat condition needs targets, a positive threshold, match and unit");
        }
    }

    @Override
    public boolean evaluate(StatisticEvaluationContext context) {
        return progress(context).completed();
    }

    @Override
    public ConditionProgress progress(StatisticEvaluationContext context) {
        if (match == TargetMatch.SUM) {
            long total = 0L;
            for (AchievementConfig.Requirement requirement : requirements) {
                total = Math.addExact(total, context.value(requirement));
            }
            return ConditionProgress.leaf(total, atLeast, total >= atLeast);
        }
        List<ConditionProgress> children = requirements.stream()
                .map(requirement -> {
                    long current = context.value(requirement);
                    return ConditionProgress.leaf(current, atLeast, current >= atLeast);
                })
                .toList();
        boolean completed = match == TargetMatch.EACH
                ? children.stream().allMatch(ConditionProgress::completed)
                : children.stream().anyMatch(ConditionProgress::completed);
        long current = match == TargetMatch.EACH
                ? children.stream().filter(ConditionProgress::completed).count()
                : (completed ? 1L : 0L);
        long target = match == TargetMatch.EACH ? children.size() : 1L;
        return ConditionProgress.group(completed, current, target, children);
    }
}
