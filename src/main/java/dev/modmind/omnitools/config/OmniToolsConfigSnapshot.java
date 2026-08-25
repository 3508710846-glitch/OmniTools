package dev.modmind.omnitools.config;

import dev.modmind.omnitools.AchievementConfig;
import dev.modmind.omnitools.CloudStorageConfig;
import dev.modmind.omnitools.ShopConfig;
import dev.modmind.omnitools.TitleConfig;
import dev.modmind.omnitools.TitleEffectConfig;
import dev.modmind.omnitools.CheckinRewardConfig;
import dev.modmind.omnitools.OnlineRewardConfig;
import dev.modmind.omnitools.permissions.CommandPermissionConfig;
import dev.modmind.omnitools.commandmenu.CommandMenuConfig;
import dev.modmind.omnitools.sidebar.SidebarConfig;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/** Immutable bundle swapped atomically after a complete configuration load. */
public record OmniToolsConfigSnapshot(OmniToolsRootConfig root, CheckinRewardConfig rewards,
                                    OnlineRewardConfig onlineRewards,
                                    ShopConfig shop, TitleConfig titles, TitleEffectConfig titleEffects,
                                    CloudStorageConfig cloudStorage, AchievementConfig achievements,
                                    CommandMenuConfig commandMenus,
                                    SidebarConfig sidebar,
                                    CommandPermissionConfig commandPermissions,
                                    Map<ModuleId, ModuleStatus> statuses, long revision) {
    public OmniToolsConfigSnapshot {
        EnumMap<ModuleId, ModuleStatus> copy = new EnumMap<>(ModuleId.class);
        if (statuses != null) {
            copy.putAll(statuses);
        }
        for (ModuleId module : ModuleId.values()) {
            copy.putIfAbsent(module, root != null && root.enabled(module)
                    ? ModuleStatus.ENABLED : ModuleStatus.DISABLED);
        }
        statuses = Map.copyOf(copy);
        commandPermissions = commandPermissions == null ? CommandPermissionConfig.defaults() : commandPermissions;
    }

    public boolean enabled(ModuleId module) {
        return statuses.getOrDefault(module, ModuleStatus.INVALID) == ModuleStatus.ENABLED;
    }

    public ZoneId zoneId() {
        return ZoneId.of(root.timezone());
    }

    public boolean placeholderApiEnabled() {
        return root.placeholderApiEnabled();
    }
}
