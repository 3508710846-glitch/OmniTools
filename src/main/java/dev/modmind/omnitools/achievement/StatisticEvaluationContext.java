package dev.modmind.omnitools.achievement;

import net.minecraft.server.level.ServerPlayer;

import dev.modmind.omnitools.AchievementConfig;

import java.util.HashMap;
import java.util.Map;

/** Per-player, per-check cache for vanilla statistic reads. */
public final class StatisticEvaluationContext {
    private final ServerPlayer player;
    private final Map<CacheKey, Long> values = new HashMap<>();

    public StatisticEvaluationContext(ServerPlayer player) {
        this.player = player;
    }

    public ServerPlayer player() {
        return player;
    }

    public long value(AchievementConfig.Requirement requirement) {
        long raw = values.computeIfAbsent(new CacheKey(requirement.type(), requirement.targetId()), ignored ->
                (long) player.getStats().getValue(requirement.stat()));
        try {
            return Math.multiplyExact(raw, requirement.multiplier());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private record CacheKey(AchievementConfig.RequirementType type, String targetId) {
    }
}
