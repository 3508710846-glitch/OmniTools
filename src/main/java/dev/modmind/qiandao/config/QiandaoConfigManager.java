package dev.modmind.qiandao.config;

import dev.modmind.qiandao.AchievementConfig;
import dev.modmind.qiandao.CloudStorageConfig;
import dev.modmind.qiandao.ShopConfig;
import dev.modmind.qiandao.TitleConfig;
import dev.modmind.qiandao.TitleEffectConfig;
import dev.modmind.qiandao.CheckinRewardConfig;
import dev.modmind.qiandao.OnlineRewardConfig;
import net.minecraft.server.MinecraftServer;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

/** Loads and publishes a complete configuration snapshot atomically. */
public final class QiandaoConfigManager {
    private final AtomicLong revisions = new AtomicLong();
    private volatile QiandaoConfigSnapshot snapshot;

    public QiandaoConfigManager() {
        this.snapshot = emptySnapshot();
    }

    public QiandaoConfigSnapshot snapshot() {
        return snapshot;
    }

    public synchronized QiandaoConfigSnapshot load(MinecraftServer server) {
        ConfigMigration.migrate();
        QiandaoRootConfig root;
        try {
            root = QiandaoRootConfig.load(ConfigPaths.rootConfig());
            CheckinRewardConfig dailyRewards = root.enabled(ModuleId.DAILY_CHECKIN)
                    ? CheckinRewardConfig.load() : CheckinRewardConfig.empty();
            OnlineRewardConfig onlineRewards = root.enabled(ModuleId.ONLINE_REWARD)
                    ? OnlineRewardConfig.load() : OnlineRewardConfig.empty();
            CheckinRewardConfig rewards = CheckinRewardConfig.withOnlineRewards(dailyRewards, onlineRewards);
            ShopConfig shop = root.enabled(ModuleId.SHOP)
                    ? ShopConfig.load(server.registryAccess()) : ShopConfig.empty();
            TitleConfig titles = root.enabled(ModuleId.TITLES) ? TitleConfig.load() : TitleConfig.empty();
            TitleEffectConfig effects = root.enabled(ModuleId.TITLE_EFFECTS)
                    ? TitleEffectConfig.load() : TitleEffectConfig.empty();
            CloudStorageConfig storage = root.enabled(ModuleId.CLOUD_STORAGE)
                    ? CloudStorageConfig.load() : CloudStorageConfig.defaultConfig();
            AchievementConfig achievements = root.enabled(ModuleId.ACHIEVEMENTS)
                    ? AchievementConfig.load() : AchievementConfig.empty();
            EnumMap<ModuleId, ModuleStatus> statuses = new EnumMap<>(ModuleId.class);
            for (ModuleId module : ModuleId.values()) {
                statuses.put(module, root.enabled(module) ? ModuleStatus.ENABLED : ModuleStatus.DISABLED);
            }
            QiandaoConfigSnapshot candidate = new QiandaoConfigSnapshot(root, rewards, onlineRewards, shop, titles, effects,
                    storage, achievements, statuses, revisions.incrementAndGet());
            ConfigValidator.validate(candidate);
            snapshot = candidate;
            return candidate;
        } catch (RuntimeException | java.io.IOException exception) {
            System.err.println("[qiandao] Configuration reload rejected; keeping the previous snapshot: "
                    + exception.getMessage());
            return snapshot;
        }
    }

    private static QiandaoConfigSnapshot emptySnapshot() {
        QiandaoRootConfig root = QiandaoRootConfig.defaults();
        EnumMap<ModuleId, ModuleStatus> statuses = new EnumMap<>(ModuleId.class);
        for (ModuleId module : ModuleId.values()) {
            statuses.put(module, ModuleStatus.DISABLED);
        }
        return new QiandaoConfigSnapshot(root, CheckinRewardConfig.empty(), OnlineRewardConfig.empty(), ShopConfig.empty(),
                TitleConfig.empty(), TitleEffectConfig.empty(), CloudStorageConfig.defaultConfig(),
                AchievementConfig.empty(), statuses, 0L);
    }
}
