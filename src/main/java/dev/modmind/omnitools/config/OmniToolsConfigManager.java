package dev.modmind.omnitools.config;

import dev.modmind.omnitools.AchievementConfig;
import dev.modmind.omnitools.CloudStorageConfig;
import dev.modmind.omnitools.ShopConfig;
import dev.modmind.omnitools.TitleConfig;
import dev.modmind.omnitools.TitleEffectConfig;
import dev.modmind.omnitools.CheckinRewardConfig;
import dev.modmind.omnitools.OnlineRewardConfig;
import net.minecraft.server.MinecraftServer;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

/** Loads and publishes a complete configuration snapshot atomically. */
public final class OmniToolsConfigManager {
    private final AtomicLong revisions = new AtomicLong();
    private volatile OmniToolsConfigSnapshot snapshot;

    public OmniToolsConfigManager() {
        this.snapshot = emptySnapshot();
    }

    public OmniToolsConfigSnapshot snapshot() {
        return snapshot;
    }

    public synchronized OmniToolsConfigSnapshot load(MinecraftServer server) {
        ConfigMigration.migrate();
            OmniToolsRootConfig root;
        try {
            root = OmniToolsRootConfig.load(ConfigPaths.rootConfig());
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
            OmniToolsConfigSnapshot candidate = new OmniToolsConfigSnapshot(root, rewards, onlineRewards, shop, titles, effects,
                    storage, achievements, statuses, revisions.incrementAndGet());
            ConfigValidator.validate(candidate);
            snapshot = candidate;
            return candidate;
        } catch (RuntimeException | java.io.IOException exception) {
            System.err.println("[omnitools] Configuration reload rejected; keeping the previous snapshot: "
                    + exception.getMessage());
            return snapshot;
        }
    }

    private static OmniToolsConfigSnapshot emptySnapshot() {
        OmniToolsRootConfig root = OmniToolsRootConfig.defaults();
        EnumMap<ModuleId, ModuleStatus> statuses = new EnumMap<>(ModuleId.class);
        for (ModuleId module : ModuleId.values()) {
            statuses.put(module, ModuleStatus.DISABLED);
        }
        return new OmniToolsConfigSnapshot(root, CheckinRewardConfig.empty(), OnlineRewardConfig.empty(), ShopConfig.empty(),
                TitleConfig.empty(), TitleEffectConfig.empty(), CloudStorageConfig.defaultConfig(),
                AchievementConfig.empty(), statuses, 0L);
    }
}
