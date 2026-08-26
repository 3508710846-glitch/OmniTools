package dev.modmind.omnitools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One-way, non-destructive migration from the pre-1.1 root config layout. */
public final class ConfigMigration {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigMigration() {
    }

    public static void migrate() {
        migrate(ConfigPaths.root(), FabricLoader.getInstance().getConfigDir());
    }

    /** Package-visible isolated entry point used by migration tests. */
    static void migrate(Path configRoot, Path legacyConfigRoot) {
        try {
            Files.createDirectories(configRoot);
            Files.createDirectories(legacyDir(configRoot));
            migrateRootConfig(configRoot);
            migrateRewards(configRoot, legacyConfigRoot);
            copy(configRoot, legacyConfigRoot, "omnitools-shop.json", ModuleId.SHOP, false);
            copy(configRoot, legacyConfigRoot, "omnitools-titles.json", ModuleId.TITLES, true);
            copy(configRoot, legacyConfigRoot, "omnitools-title-effects.json", ModuleId.TITLE_EFFECTS, false);
            copy(configRoot, legacyConfigRoot, "omnitools-achievements.json", ModuleId.ACHIEVEMENTS, false);
            copy(configRoot, legacyConfigRoot, "omnitools-cloud-storage.json", ModuleId.CLOUD_STORAGE, false);
        } catch (IOException exception) {
            System.err.println("[omnitools] Configuration migration failed: " + exception.getMessage());
        }
    }

    /**
     * Root config migration is intentionally conservative: modules introduced after the legacy
     * root layout stay disabled until an administrator opts in. The original file is copied before
     * any normalization so a failed upgrade is fully reversible.
     */
    private static void migrateRootConfig(Path configRoot) throws IOException {
        Path rootConfig = rootConfig(configRoot);
        if (!Files.exists(rootConfig)) {
            return;
        }
        JsonObject root = readObject(rootConfig);
        int version = root.has("format_version") && root.get("format_version").isJsonPrimitive()
                ? root.get("format_version").getAsInt() : 1;
        if (version >= OmniToolsRootConfig.CURRENT_FORMAT_VERSION) {
            return;
        }
        Path backup = rootConfig.resolveSibling("config.json.v" + version + ".bak-" + System.currentTimeMillis());
        Files.copy(rootConfig, backup, StandardCopyOption.COPY_ATTRIBUTES);

        JsonObject global = object(root, "global");
        if (!global.has("debug")) {
            global.addProperty("debug", false);
        }
        if (!global.has("timezone")) {
            global.addProperty("timezone", "Asia/Shanghai");
        }
        if (!global.has("language")) {
            global.addProperty("language", "zh_cn");
        }
        if (!global.has("data_retention")) {
            global.addProperty("data_retention", "full");
        }
        JsonObject security = object(global, "reward_security");
        if (!security.has("allow_command_rewards")) {
            security.addProperty("allow_command_rewards", false);
        }
        if (!security.has("max_command_length")) {
            security.addProperty("max_command_length", 1_024);
        }
        global.add("reward_security", security);
        JsonObject commandSecurity = object(global, "command_security");
        if (!commandSecurity.has("allowed_roots")) {
            JsonArray legacyRoots = new JsonArray();
            legacyRoots.add(CommandSecurityConfig.PERMISSIVE_ROOT);
            commandSecurity.add("allowed_roots", legacyRoots);
        }
        if (!commandSecurity.has("max_command_length")) {
            commandSecurity.addProperty("max_command_length", CommandSecurityConfig.DEFAULT_MAX_COMMAND_LENGTH);
        }
        if (!commandSecurity.has("cooldown_ticks")) {
            commandSecurity.addProperty("cooldown_ticks", CommandSecurityConfig.LEGACY_COOLDOWN_TICKS);
        }
        global.add("command_security", commandSecurity);
        root.add("global", global);

        JsonObject integrations = object(root, "integrations");
        JsonObject placeholderApi = object(integrations, "placeholder_api");
        if (!placeholderApi.has("enabled")) {
            placeholderApi.addProperty("enabled", true);
        }
        integrations.add("placeholder_api", placeholderApi);
        root.add("integrations", integrations);

        JsonObject modules = object(root, "modules");
        for (ModuleId module : ModuleId.values()) {
            if (modules.has(module.id()) && modules.get(module.id()).isJsonObject()) {
                continue;
            }
            JsonObject status = new JsonObject();
            boolean enabled = module != ModuleId.PERMISSIONS;
            if (module == ModuleId.COMMAND_MENU || module == ModuleId.SIDEBAR) {
                enabled = false;
            }
            if (module == ModuleId.CDK && version < 4) {
                enabled = false;
            }
            status.addProperty("enabled", enabled);
            modules.add(module.id(), status);
        }
        root.add("modules", modules);
        // v4 keeps the existing module files but introduces a shared common/ directory.
        // CommonConfig creates the three bounded, data-only files on first load.
        root.addProperty("format_version", OmniToolsRootConfig.CURRENT_FORMAT_VERSION);
        write(rootConfig, root);
        System.out.println("[omnitools] Migrated root config from v" + version + " to v"
                + OmniToolsRootConfig.CURRENT_FORMAT_VERSION + "; backup: " + backup.getFileName());
    }

    private static void migrateRewards(Path root, Path legacyConfigRoot) throws IOException {
        Path source = findLegacyConfig(legacyConfigRoot, "omnitools-rewards.json");
        Path daily = moduleConfig(root, ModuleId.DAILY_CHECKIN);
        Path online = moduleConfig(root, ModuleId.ONLINE_REWARD);
        if (source == null || (Files.exists(daily) && Files.exists(online))) {
            return;
        }
        JsonObject old = readObject(source);
        JsonObject dailyRoot = new JsonObject();
        dailyRoot.addProperty("format_version", 1);
        copyProperty(old, dailyRoot, "dailyCoins");
        copyProperty(old, dailyRoot, "monthlyRewards");
        JsonObject onlineRoot = new JsonObject();
        onlineRoot.addProperty("format_version", 1);
        JsonArray rewards = new JsonArray();
        JsonElement oldRewards = old.get("onlineTimeRewards");
        if (oldRewards != null && oldRewards.isJsonArray()) {
            for (int index = 0; index < oldRewards.getAsJsonArray().size(); index++) {
                JsonElement entry = oldRewards.getAsJsonArray().get(index);
                if (entry.isJsonObject()) {
                    JsonObject reward = entry.getAsJsonObject().deepCopy();
                    if (!reward.has("id") && reward.has("minutes")) {
                        reward.addProperty("id", "online_" + reward.get("minutes").getAsInt() + "m");
                    }
                    rewards.add(reward);
                }
            }
        }
        onlineRoot.add("rewards", rewards);
        writeIfMissing(daily, dailyRoot);
        writeIfMissing(online, onlineRoot);
        archive(root, source);
    }

    private static void copy(Path configRoot, Path legacyConfigRoot, String oldName, ModuleId module,
                             boolean stripPlayers) throws IOException {
        Path source = findLegacyConfig(legacyConfigRoot, oldName);
        Path target = moduleConfig(configRoot, module);
        if (source == null || Files.exists(target)) {
            return;
        }
        JsonElement content = read(source);
        if (module == ModuleId.SHOP && content.isJsonArray()) {
            JsonObject wrapped = new JsonObject();
            wrapped.addProperty("format_version", 1);
            wrapped.add("products", content);
            content = wrapped;
        } else if (module == ModuleId.TITLES && content.isJsonObject()) {
            JsonObject wrapped = content.getAsJsonObject().deepCopy();
            wrapped.addProperty("format_version", 1);
            if (stripPlayers) {
                wrapped.remove("players");
            }
            content = wrapped;
        } else if (content.isJsonObject()) {
            JsonObject wrapped = content.getAsJsonObject().deepCopy();
            if (!wrapped.has("format_version")) {
                wrapped.addProperty("format_version", 1);
            }
            content = wrapped;
        }
        writeIfMissing(target, content);
        archive(configRoot, source);
    }

    /**
     * Finds a root-level legacy configuration file, preferring the current
     * {@code omnitools-*} name and falling back to the pre-rename
     * {@code qiandao-*} name.  The returned path is the actual source so the
     * archive manifest preserves which file was migrated.
     */
    private static Path findLegacyConfig(Path legacyConfigRoot, String currentName) {
        Path current = legacyConfigRoot.resolve(currentName);
        if (Files.exists(current)) {
            return current;
        }

        String fallbackName = null;
        if (currentName.startsWith("omnitools-")) {
            fallbackName = "qiandao-" + currentName.substring("omnitools-".length());
        } else if (currentName.startsWith("qiandao-")) {
            fallbackName = "omnitools-" + currentName.substring("qiandao-".length());
        }
        if (fallbackName == null) {
            return null;
        }

        Path fallback = legacyConfigRoot.resolve(fallbackName);
        return Files.exists(fallback) ? fallback : null;
    }

    private static JsonElement read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, JsonElement.class);
        }
    }

    private static JsonObject readObject(Path path) throws IOException {
        JsonElement root = read(path);
        if (root == null || !root.isJsonObject()) {
            throw new IOException("legacy config root is not an object: " + path.getFileName());
        }
        return root.getAsJsonObject();
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject().deepCopy() : new JsonObject();
    }

    private static void copyProperty(JsonObject source, JsonObject target, String key) {
        JsonElement value = source.get(key);
        if (value != null) {
            target.add(key, value.deepCopy());
        }
    }

    private static void writeIfMissing(Path path, JsonElement root) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static void write(Path path, JsonElement root) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "config-migration-", ".json");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void archive(Path root, Path source) throws IOException {
        Path archived = legacyDir(root).resolve(source.getFileName());
        if (!Files.exists(archived)) {
            Files.copy(source, archived, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path manifest = legacyDir(root).resolve("manifest.json");
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("source", source.toString());
        entry.put("archived", archived.toString());
        entry.put("format_version", "1");
        entry.put("migrated_at", Instant.now().toString());
        JsonArray entries = new JsonArray();
        if (Files.exists(manifest)) {
            try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
                JsonElement value = GSON.fromJson(reader, JsonElement.class);
                if (value != null && value.isJsonArray()) {
                    entries = value.getAsJsonArray();
                }
            } catch (RuntimeException ignored) {
                // A fresh manifest is safer than preventing the config migration.
            }
        }
        JsonObject object = new JsonObject();
        entry.forEach(object::addProperty);
        entries.add(object);
        try (Writer writer = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8)) {
            GSON.toJson(entries, writer);
        }
    }

    private static Path rootConfig(Path root) {
        return root.resolve("config.json");
    }

    private static Path moduleConfig(Path root, ModuleId module) {
        return root.resolve(module.id()).resolve("config.json");
    }

    private static Path legacyDir(Path root) {
        return root.resolve("legacy");
    }
}
