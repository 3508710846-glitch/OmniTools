package dev.modmind.omnitools.config;

import dev.modmind.omnitools.AchievementConfig;
import dev.modmind.omnitools.CloudStorageConfig;
import dev.modmind.omnitools.ShopConfig;
import dev.modmind.omnitools.TitleConfig;
import dev.modmind.omnitools.TitleEffectConfig;
import dev.modmind.omnitools.CheckinRewardConfig;
import dev.modmind.omnitools.OnlineRewardConfig;
import dev.modmind.omnitools.commandmenu.CommandMenuConfig;
import dev.modmind.omnitools.sidebar.SidebarConfig;
import net.minecraft.server.MinecraftServer;
import dev.modmind.omnitools.permissions.CommandPermissionConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
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

    /** Reloads all administrator-managed files, publishing them only after complete validation. */
    public synchronized ReloadResult reload(MinecraftServer server) {
        ConfigMigration.migrate();
        OmniToolsConfigSnapshot previous = snapshot;
        try {
            OmniToolsConfigSnapshot candidate = buildCandidate(server, OmniToolsRootConfig.load(ConfigPaths.rootConfig()));
            OmniToolsConfigSnapshot published = publish(candidate);
            return new ReloadResult(true, "", previous, published);
        } catch (RuntimeException | IOException exception) {
            String message = message(exception);
            System.err.println("[omnitools] Configuration reload rejected; keeping the previous snapshot: " + message);
            return new ReloadResult(false, message, previous, previous);
        }
    }

    /**
     * Changes exactly one root module flag as a transaction. The candidate snapshot is loaded and
     * validated before the root config is written, so failed enables cannot split disk and runtime state.
     */
    public synchronized ModuleUpdateResult updateModuleEnabled(MinecraftServer server, ModuleId module,
                                                               boolean enabled) {
        OmniToolsConfigSnapshot previous = snapshot;
        if (module == null) {
            return new ModuleUpdateResult(false, null, enabled, "Module is required", previous, previous);
        }
        if (previous.enabled(module) == enabled) {
            return new ModuleUpdateResult(true, module, enabled, "", previous, previous);
        }
        try {
            OmniToolsRootConfig updatedRoot = previous.root().withModuleEnabled(module, enabled);
            OmniToolsConfigSnapshot candidate = buildCandidate(server, updatedRoot);
            writeRootAtomically(updatedRoot);
            OmniToolsConfigSnapshot published = publish(candidate);
            return new ModuleUpdateResult(true, module, enabled, "", previous, published);
        } catch (RuntimeException | IOException exception) {
            String message = message(exception);
            System.err.println("[omnitools] Module update rejected for " + module.id() + "; keeping the previous "
                    + "snapshot: " + message);
            return new ModuleUpdateResult(false, module, enabled, message, previous, previous);
        }
    }

    /** Compatibility entry point for startup callers that only need the active snapshot. */
    public synchronized OmniToolsConfigSnapshot load(MinecraftServer server) {
        return reload(server).current();
    }

    private OmniToolsConfigSnapshot buildCandidate(MinecraftServer server, OmniToolsRootConfig root)
            throws IOException {
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
        CommandMenuConfig commandMenus = root.enabled(ModuleId.COMMAND_MENU)
                ? CommandMenuConfig.load() : CommandMenuConfig.empty();
        SidebarConfig sidebar = root.enabled(ModuleId.SIDEBAR)
                ? SidebarConfig.load() : SidebarConfig.empty();
        CommandPermissionConfig commandPermissions = root.enabled(ModuleId.PERMISSIONS)
                ? CommandPermissionConfig.load() : CommandPermissionConfig.defaults();
        EnumMap<ModuleId, ModuleStatus> statuses = new EnumMap<>(ModuleId.class);
        for (ModuleId configuredModule : ModuleId.values()) {
            statuses.put(configuredModule, root.enabled(configuredModule)
                    ? ModuleStatus.ENABLED : ModuleStatus.DISABLED);
        }
        OmniToolsConfigSnapshot candidate = new OmniToolsConfigSnapshot(root, rewards, onlineRewards, shop, titles, effects,
                storage, achievements, commandMenus, sidebar, commandPermissions, statuses, revisions.get() + 1L);
        ConfigValidator.validate(candidate);
        return candidate;
    }

    private OmniToolsConfigSnapshot publish(OmniToolsConfigSnapshot candidate) {
        long revision = revisions.incrementAndGet();
        OmniToolsConfigSnapshot published = new OmniToolsConfigSnapshot(candidate.root(), candidate.rewards(),
                candidate.onlineRewards(), candidate.shop(), candidate.titles(), candidate.titleEffects(),
                candidate.cloudStorage(), candidate.achievements(), candidate.commandMenus(),
                candidate.sidebar(), candidate.commandPermissions(), candidate.statuses(), revision);
        snapshot = published;
        return published;
    }

    private static void writeRootAtomically(OmniToolsRootConfig root) throws IOException {
        Path destination = ConfigPaths.rootConfig();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "config.json.", ".tmp");
        try {
            OmniToolsRootConfig.save(temporary, root);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public record ReloadResult(boolean success, String message, OmniToolsConfigSnapshot previous,
                               OmniToolsConfigSnapshot current) {
    }

    public record ModuleUpdateResult(boolean success, ModuleId module, boolean enabled, String message,
                                     OmniToolsConfigSnapshot previous, OmniToolsConfigSnapshot current) {
    }

    private static OmniToolsConfigSnapshot emptySnapshot() {
        OmniToolsRootConfig root = OmniToolsRootConfig.defaults();
        EnumMap<ModuleId, ModuleStatus> statuses = new EnumMap<>(ModuleId.class);
        for (ModuleId module : ModuleId.values()) {
            statuses.put(module, ModuleStatus.DISABLED);
        }
        return new OmniToolsConfigSnapshot(root, CheckinRewardConfig.empty(), OnlineRewardConfig.empty(), ShopConfig.empty(),
                TitleConfig.empty(), TitleEffectConfig.empty(), CloudStorageConfig.defaultConfig(),
                AchievementConfig.empty(), CommandMenuConfig.empty(), SidebarConfig.empty(),
                CommandPermissionConfig.defaults(), statuses, 0L);
    }
}
