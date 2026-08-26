package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.config.OmniToolsConfigManager;
import dev.modmind.omnitools.config.OmniToolsConfigSnapshot;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/** Coordinates transactional module changes with the shared runtime compensation path. */
public final class ModuleControlService {
    private final OmniToolsConfigManager configManager;

    public ModuleControlService(OmniToolsConfigManager configManager) {
        this.configManager = configManager;
    }

    public OmniToolsConfigManager.ReloadResult reload(MinecraftServer server) {
        OmniToolsConfigManager.ReloadResult result = configManager.reload(server);
        if (result.success()) {
            ModMindEntry.applyRuntimeConfigChange(server, result.previous(), result.current());
        }
        return result;
    }

    public OmniToolsConfigManager.ModuleUpdateResult updateModuleEnabled(MinecraftServer server, ModuleId module,
                                                                           boolean enabled) {
        Optional<DependencyBlock> blocked = dependencyBlock(configManager.snapshot(), module, enabled);
        if (blocked.isPresent()) {
            OmniToolsConfigSnapshot current = configManager.snapshot();
            return new OmniToolsConfigManager.ModuleUpdateResult(false, module, enabled,
                    blocked.get().translationKey(), current, current);
        }
        OmniToolsConfigManager.ModuleUpdateResult result = configManager.updateModuleEnabled(server, module, enabled);
        if (result.success() && result.previous() != result.current()) {
            ModMindEntry.applyRuntimeConfigChange(server, result.previous(), result.current());
        }
        return result;
    }

    public Optional<DependencyBlock> dependencyBlock(OmniToolsConfigSnapshot snapshot, ModuleId module,
                                                      boolean enabled) {
        if (snapshot == null || module == null) {
            return Optional.empty();
        }
        if (module == ModuleId.TITLE_EFFECTS && enabled && !snapshot.enabled(ModuleId.TITLES)) {
            return Optional.of(DependencyBlock.TITLE_EFFECTS_REQUIRES_TITLES);
        }
        if (module == ModuleId.TITLES && !enabled && snapshot.enabled(ModuleId.TITLE_EFFECTS)
                && !snapshot.titleEffects().definitions().isEmpty()) {
            return Optional.of(DependencyBlock.TITLES_REQUIRED_BY_EFFECTS);
        }
        if (module == ModuleId.TITLES && !enabled && hasTitleRewards(snapshot)) {
            return Optional.of(DependencyBlock.TITLES_REQUIRED_BY_REWARDS);
        }
        return Optional.empty();
    }

    private static boolean hasTitleRewards(OmniToolsConfigSnapshot snapshot) {
        boolean daily = snapshot.rewards().dailyRewards().stream()
                .anyMatch(reward -> reward.type() == dev.modmind.omnitools.reward.RewardType.TITLE);
        boolean monthly = snapshot.rewards().monthlyRewards().values().stream().flatMap(java.util.Collection::stream)
                .anyMatch(reward -> reward.type() == dev.modmind.omnitools.reward.RewardType.TITLE);
        boolean online = snapshot.rewards().onlineTimeRewards().stream()
                .flatMap(milestone -> milestone.rewards().stream())
                .anyMatch(reward -> reward.type() == dev.modmind.omnitools.reward.RewardType.TITLE);
        boolean achievements = snapshot.achievements().achievements().stream().flatMap(achievement -> achievement.rewards().stream())
                .anyMatch(reward -> reward.type() == dev.modmind.omnitools.reward.RewardType.TITLE);
        boolean cdk = snapshot.cdk().campaigns().stream().flatMap(campaign -> campaign.rewards().stream())
                .anyMatch(reward -> reward.type() == dev.modmind.omnitools.reward.RewardType.TITLE);
        return daily || monthly || online || achievements || cdk;
    }

    public enum DependencyBlock {
        TITLE_EFFECTS_REQUIRES_TITLES("gui.omnitools.modules.blocked.title_effects_requires_titles"),
        TITLES_REQUIRED_BY_EFFECTS("gui.omnitools.modules.blocked.titles_required_by_effects"),
        TITLES_REQUIRED_BY_REWARDS("gui.omnitools.modules.blocked.titles_required_by_rewards");

        private final String translationKey;

        DependencyBlock(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }
}
