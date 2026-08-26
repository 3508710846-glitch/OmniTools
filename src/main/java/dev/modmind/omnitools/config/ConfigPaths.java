package dev.modmind.omnitools.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/** Centralized paths for administrator-editable omnitools configuration. */
public final class ConfigPaths {
    private ConfigPaths() {
    }

    public static Path root() {
        return FabricLoader.getInstance().getConfigDir().resolve("omnitools");
    }

    public static Path rootConfig() {
        return root().resolve("config.json");
    }

    public static Path moduleDir(ModuleId module) {
        return root().resolve(module.id());
    }

    public static Path moduleConfig(ModuleId module) {
        return moduleDir(module).resolve("config.json");
    }

    /** Shared, cross-module configuration files. */
    public static Path commonDir() {
        return root().resolve("common");
    }

    public static Path commonRewards() {
        return commonDir().resolve("rewards.json");
    }

    public static Path commonConditions() {
        return commonDir().resolve("conditions.json");
    }

    public static Path commonTexts() {
        return commonDir().resolve("texts.json");
    }

    public static Path commandMenuDir() {
        return moduleDir(ModuleId.COMMAND_MENU);
    }

    public static Path commandMenuRegistry() {
        return commandMenuDir().resolve("config.json");
    }

    public static Path commandMenuFiles() {
        return commandMenuDir().resolve("menus");
    }

    public static Path legacyDir() {
        return root().resolve("legacy");
    }

    public static Path oldConfig(String fileName) {
        return FabricLoader.getInstance().getConfigDir().resolve(fileName);
    }
}
