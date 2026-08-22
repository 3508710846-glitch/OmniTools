package dev.modmind.qiandao.config;

import dev.modmind.qiandao.AchievementConfig;
import dev.modmind.qiandao.CloudStorageConfig;
import dev.modmind.qiandao.ShopConfig;
import dev.modmind.qiandao.TitleConfig;
import dev.modmind.qiandao.TitleEffectConfig;
import dev.modmind.qiandao.CheckinRewardConfig;
import dev.modmind.qiandao.OnlineRewardConfig;

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
