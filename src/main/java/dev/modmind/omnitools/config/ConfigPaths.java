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

    public static Path legacyDir() {
        return root().resolve("legacy");
    }

    public static Path oldConfig(String fileName) {
        return FabricLoader.getInstance().getConfigDir().resolve(fileName);
    }
}
