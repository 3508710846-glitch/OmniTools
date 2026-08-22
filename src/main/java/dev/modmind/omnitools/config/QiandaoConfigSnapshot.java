package dev.modmind.omnitools.config;

import dev.modmind.omnitools.AchievementConfig;
import dev.modmind.omnitools.CloudStorageConfig;
import dev.modmind.omnitools.ShopConfig;
import dev.modmind.omnitools.TitleConfig;
import dev.modmind.omnitools.TitleEffectConfig;
import dev.modmind.omnitools.CheckinRewardConfig;
import dev.modmind.omnitools.OnlineRewardConfig;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/** Immutable bundle swapped atomically after a complete configuration load. */
public record QiandaoConfigSnapshot(QiandaoRootConfig root, CheckinRewardConfig rewards,
                                    OnlineRewardConfig onlineRewards,
                                    ShopConfig shop, TitleConfig titles, TitleEffectConfig titleEffects,
                                    CloudStorageConfig cloudStorage, AchievementConfig achievements,
                                    Map<ModuleId, ModuleStatus> statuses, long revision) {
    public QiandaoConfigSnapshot {
        EnumMap<ModuleId, ModuleStatus> copy = new EnumMap<>(ModuleId.class);
        if (statuses != null) {
            copy.putAll(statuses);
        }
        for (ModuleId module : ModuleId.values()) {
            copy.putIfAbsent(module, root != null && root.enabled(module)
                    ? ModuleStatus.ENABLED : ModuleStatus.DISABLED);
        }
        statuses = Map.copyOf(copy);
    }

    public boolean enabled(ModuleId module) {
        return statuses.getOrDefault(module, ModuleStatus.INVALID) == ModuleStatus.ENABLED;
    }

    public ZoneId zoneId() {
        return ZoneId.of(root.timezone());
    }
}
