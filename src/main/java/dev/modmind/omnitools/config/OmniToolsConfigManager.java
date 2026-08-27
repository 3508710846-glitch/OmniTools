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
import dev.modmind.omnitools.cdk.CdkConfig;
import dev.modmind.omnitools.cdk.CdkData;
import dev.modmind.omnitools.leaderboard.LeaderboardConfig;
import dev.modmind.omnitools.packages.PackageConfig;
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
    private final ConfigModuleRegistry moduleRegistry = createModuleRegistry();
    private volatile OmniToolsConfigSnapshot snapshot;

    public OmniToolsConfigManager() {
        this.snapshot = emptySnapshot();
    }

    public OmniToolsConfigSnapshot snapshot() {
        return snapshot;
    }

    public ConfigModuleRegistry moduleRegistry() {
        return moduleRegistry;
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
     * Replaces one module's typed configuration while retaining the active root and common files.
     * Common templates intentionally require a full reload because one template can affect multiple
     * module definitions.
     */
    public synchronized ModuleReloadResult reloadModule(MinecraftServer server, ModuleId module) {
        OmniToolsConfigSnapshot previous = snapshot;
        if (module == null) {
            return new ModuleReloadResult(false, null, "Module is required", previous, previous);
        }
        try {
            LoadContext context = new LoadContext(server, server.registryAccess(), previous.root(), previous.common());
            java.util.Map<ModuleId, Object> loaded = snapshotModules(previous);
            loaded.put(module, moduleRegistry.load(module, context));
            OmniToolsConfigSnapshot candidate = buildSnapshot(server, previous.root(), previous.common(), loaded);
            OmniToolsConfigSnapshot published = publish(candidate);
            return new ModuleReloadResult(true, module, "", previous, published);
        } catch (RuntimeException | IOException exception) {
            String message = message(exception);
            System.err.println("[omnitools] Module reload rejected for " + module.id()
                    + "; keeping the previous snapshot: " + message);
            return new ModuleReloadResult(false, module, message, previous, previous);
        } catch (Exception exception) {
            String message = message(exception);
            System.err.println("[omnitools] Module reload rejected for " + module.id()
                    + "; keeping the previous snapshot: " + message);
            return new ModuleReloadResult(false, module, message, previous, previous);
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
        CommonConfig common = CommonConfig.load(server.registryAccess());
        LoadContext loadContext = new LoadContext(server, server.registryAccess(), root, common);
        java.util.Map<ModuleId, Object> loaded;
        try {
            loaded = moduleRegistry.loadAll(loadContext);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load a configuration module", exception);
        }
        return buildSnapshot(server, root, common, loaded);
    }

    private OmniToolsConfigSnapshot buildSnapshot(MinecraftServer server, OmniToolsRootConfig root,
                                                  CommonConfig common, java.util.Map<ModuleId, Object> loaded) {
        CheckinRewardConfig dailyRewards = moduleConfig(loaded, ModuleId.DAILY_CHECKIN, CheckinRewardConfig.empty());
        OnlineRewardConfig onlineRewards = moduleConfig(loaded, ModuleId.ONLINE_REWARD, OnlineRewardConfig.empty());
        CheckinRewardConfig rewards = CheckinRewardConfig.withOnlineRewards(dailyRewards, onlineRewards);
        ShopConfig shop = moduleConfig(loaded, ModuleId.SHOP, ShopConfig.empty());
        TitleConfig titles = moduleConfig(loaded, ModuleId.TITLES, TitleConfig.empty());
        TitleEffectConfig effects = moduleConfig(loaded, ModuleId.TITLE_EFFECTS, TitleEffectConfig.empty());
        CloudStorageConfig storage = moduleConfig(loaded, ModuleId.CLOUD_STORAGE, CloudStorageConfig.defaultConfig());
        AchievementConfig achievements = moduleConfig(loaded, ModuleId.ACHIEVEMENTS, AchievementConfig.empty());
        CdkConfig cdk = moduleConfig(loaded, ModuleId.CDK, CdkConfig.empty());
        if (root.enabled(ModuleId.CDK)) {
            CdkData.get(server).validateConfiguration(cdk);
        }
        CommandMenuConfig commandMenus = moduleConfig(loaded, ModuleId.COMMAND_MENU, CommandMenuConfig.empty());
        SidebarConfig sidebar = moduleConfig(loaded, ModuleId.SIDEBAR, SidebarConfig.empty());
        LeaderboardConfig leaderboards = moduleConfig(loaded, ModuleId.LEADERBOARDS, LeaderboardConfig.empty());
        PackageConfig packages = moduleConfig(loaded, ModuleId.PACKAGES, PackageConfig.empty());
        CommandPermissionConfig commandPermissions = moduleConfig(loaded, ModuleId.PERMISSIONS,
                CommandPermissionConfig.defaults());
        EnumMap<ModuleId, ModuleStatus> statuses = new EnumMap<>(ModuleId.class);
        for (ModuleId configuredModule : ModuleId.values()) {
            statuses.put(configuredModule, root.enabled(configuredModule)
                    ? ModuleStatus.ENABLED : ModuleStatus.DISABLED);
        }
        OmniToolsConfigSnapshot candidate = new OmniToolsConfigSnapshot(root, rewards, onlineRewards, shop, titles, effects,
                storage, achievements, cdk, commandMenus, sidebar, leaderboards, packages, commandPermissions, statuses, revisions.get() + 1L,
                common);
        CrossModuleValidator.validate(candidate);
        moduleRegistry.validateAll(loaded, candidate);
        return candidate;
    }

    private static java.util.Map<ModuleId, Object> snapshotModules(OmniToolsConfigSnapshot snapshot) {
        java.util.Map<ModuleId, Object> modules = new EnumMap<>(ModuleId.class);
        modules.put(ModuleId.DAILY_CHECKIN, snapshot.rewards());
        modules.put(ModuleId.ONLINE_REWARD, snapshot.onlineRewards());
        modules.put(ModuleId.ACHIEVEMENTS, snapshot.achievements());
        modules.put(ModuleId.CDK, snapshot.cdk());
        modules.put(ModuleId.SHOP, snapshot.shop());
        modules.put(ModuleId.TITLES, snapshot.titles());
        modules.put(ModuleId.TITLE_EFFECTS, snapshot.titleEffects());
        modules.put(ModuleId.CLOUD_STORAGE, snapshot.cloudStorage());
        modules.put(ModuleId.PERMISSIONS, snapshot.commandPermissions());
        modules.put(ModuleId.COMMAND_MENU, snapshot.commandMenus());
        modules.put(ModuleId.SIDEBAR, snapshot.sidebar());
        modules.put(ModuleId.LEADERBOARDS, snapshot.leaderboards());
        modules.put(ModuleId.PACKAGES, snapshot.packages());
        return modules;
    }

    @SuppressWarnings("unchecked")
    private static <T> T moduleConfig(java.util.Map<ModuleId, Object> loaded, ModuleId id, T fallback) {
        Object value = loaded.get(id);
        return value == null ? fallback : (T) value;
    }

    private static ConfigModuleRegistry createModuleRegistry() {
        ConfigModuleRegistry registry = new ConfigModuleRegistry();
        registry.register(new ConfigurableModule<CheckinRewardConfig>() {
            public ModuleId id() { return ModuleId.DAILY_CHECKIN; }
            public CheckinRewardConfig load(LoadContext context) {
                return context.root().enabled(id()) ? CheckinRewardConfig.load(context.registries(), context.common())
                        : CheckinRewardConfig.empty();
            }
        });
        registry.register(new ConfigurableModule<OnlineRewardConfig>() {
            public ModuleId id() { return ModuleId.ONLINE_REWARD; }
            public OnlineRewardConfig load(LoadContext context) {
                return context.root().enabled(id()) ? OnlineRewardConfig.load(context.registries(), context.common())
                        : OnlineRewardConfig.empty();
            }
        });
        registry.register(new ConfigurableModule<AchievementConfig>() {
            public ModuleId id() { return ModuleId.ACHIEVEMENTS; }
            public AchievementConfig load(LoadContext context) {
                return context.root().enabled(id()) ? AchievementConfig.load(context.registries(), context.common())
                        : AchievementConfig.empty();
            }
        });
        registry.register(new ConfigurableModule<CdkConfig>() {
            public ModuleId id() { return ModuleId.CDK; }
            public CdkConfig load(LoadContext context) {
                return context.root().enabled(id()) ? CdkConfig.load(context.registries(), context.common())
                        : CdkConfig.empty();
            }
        });
        registry.register(new ConfigurableModule<ShopConfig>() {
            public ModuleId id() { return ModuleId.SHOP; }
            public ShopConfig load(LoadContext context) {
                return context.root().enabled(id()) ? ShopConfig.load(context.registries()) : ShopConfig.empty();
            }
        });
        registry.register(new ConfigurableModule<TitleConfig>() {
            public ModuleId id() { return ModuleId.TITLES; }
            public TitleConfig load(LoadContext context) {
                return context.root().enabled(id()) ? TitleConfig.load() : TitleConfig.empty();
            }
        });
        registry.register(new ConfigurableModule<TitleEffectConfig>() {
            public ModuleId id() { return ModuleId.TITLE_EFFECTS; }
            public TitleEffectConfig load(LoadContext context) {
                return context.root().enabled(id()) ? TitleEffectConfig.load() : TitleEffectConfig.empty();
            }
        });
        registry.register(new ConfigurableModule<CloudStorageConfig>() {
            public ModuleId id() { return ModuleId.CLOUD_STORAGE; }
            public CloudStorageConfig load(LoadContext context) {
                return context.root().enabled(id()) ? CloudStorageConfig.load() : CloudStorageConfig.defaultConfig();
            }
        });
        registry.register(new ConfigurableModule<CommandPermissionConfig>() {
            public ModuleId id() { return ModuleId.PERMISSIONS; }
            public CommandPermissionConfig load(LoadContext context) {
                return context.root().enabled(id()) ? CommandPermissionConfig.load()
                        : CommandPermissionConfig.defaults();
            }
        });
        registry.register(new ConfigurableModule<CommandMenuConfig>() {
            public ModuleId id() { return ModuleId.COMMAND_MENU; }
            public CommandMenuConfig load(LoadContext context) {
                return context.root().enabled(id()) ? CommandMenuConfig.load() : CommandMenuConfig.empty();
            }
        });
        registry.register(new ConfigurableModule<SidebarConfig>() {
            public ModuleId id() { return ModuleId.SIDEBAR; }
            public SidebarConfig load(LoadContext context) {
                return context.root().enabled(id()) ? SidebarConfig.load() : SidebarConfig.empty();
            }
        });
        registry.register(new ConfigurableModule<LeaderboardConfig>() {
            public ModuleId id() { return ModuleId.LEADERBOARDS; }
            public LeaderboardConfig load(LoadContext context) {
                return context.root().enabled(id()) ? LeaderboardConfig.load() : LeaderboardConfig.empty();
            }
        });
        registry.register(new ConfigurableModule<PackageConfig>() {
            public ModuleId id() { return ModuleId.PACKAGES; }
            public PackageConfig load(LoadContext context) {
                return context.root().enabled(id()) ? PackageConfig.load(context.registries()) : PackageConfig.empty();
            }
        });
        return registry;
    }

    private OmniToolsConfigSnapshot publish(OmniToolsConfigSnapshot candidate) {
        long revision = revisions.incrementAndGet();
        OmniToolsConfigSnapshot published = new OmniToolsConfigSnapshot(candidate.root(), candidate.rewards(),
                candidate.onlineRewards(), candidate.shop(), candidate.titles(), candidate.titleEffects(),
                candidate.cloudStorage(), candidate.achievements(), candidate.cdk(), candidate.commandMenus(),
                candidate.sidebar(), candidate.leaderboards(), candidate.packages(), candidate.commandPermissions(), candidate.statuses(), revision, candidate.common());
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

    public record ModuleReloadResult(boolean success, ModuleId module, String message,
                                     OmniToolsConfigSnapshot previous, OmniToolsConfigSnapshot current) {
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
                AchievementConfig.empty(), CdkConfig.empty(), CommandMenuConfig.empty(), SidebarConfig.empty(),
                LeaderboardConfig.empty(), PackageConfig.empty(),
                CommandPermissionConfig.defaults(), statuses, 0L);
    }
}
