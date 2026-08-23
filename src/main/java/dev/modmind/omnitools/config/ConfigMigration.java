package dev.modmind.omnitools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
        try {
            Files.createDirectories(ConfigPaths.root());
            Files.createDirectories(ConfigPaths.legacyDir());
            migrateRewards();
            copy("omnitools-shop.json", ModuleId.SHOP, false);
            copy("omnitools-titles.json", ModuleId.TITLES, true);
            copy("omnitools-title-effects.json", ModuleId.TITLE_EFFECTS, false);
            copy("omnitools-achievements.json", ModuleId.ACHIEVEMENTS, false);
            copy("omnitools-cloud-storage.json", ModuleId.CLOUD_STORAGE, false);
        } catch (IOException exception) {
            System.err.println("[omnitools] Configuration migration failed: " + exception.getMessage());
        }
    }

    private static void migrateRewards() throws IOException {
        Path source = findLegacyConfig("omnitools-rewards.json");
        Path daily = ConfigPaths.moduleConfig(ModuleId.DAILY_CHECKIN);
        Path online = ConfigPaths.moduleConfig(ModuleId.ONLINE_REWARD);
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
        archive(source);
    }

    private static void copy(String oldName, ModuleId module, boolean stripPlayers) throws IOException {
        Path source = findLegacyConfig(oldName);
        Path target = ConfigPaths.moduleConfig(module);
        if (source == null || Files.exists(target)) {
            return;
        }
        JsonElement root = read(source);
        if (module == ModuleId.SHOP && root.isJsonArray()) {
            JsonObject wrapped = new JsonObject();
            wrapped.addProperty("format_version", 1);
            wrapped.add("products", root);
            root = wrapped;
        } else if (module == ModuleId.TITLES && root.isJsonObject()) {
            JsonObject wrapped = root.getAsJsonObject().deepCopy();
            wrapped.addProperty("format_version", 1);
            if (stripPlayers) {
                wrapped.remove("players");
            }
            root = wrapped;
        } else if (root.isJsonObject()) {
            JsonObject wrapped = root.getAsJsonObject().deepCopy();
            if (!wrapped.has("format_version")) {
                wrapped.addProperty("format_version", 1);
            }
            root = wrapped;
        }
        writeIfMissing(target, root);
        archive(source);
    }

    /**
     * Finds a root-level legacy configuration file, preferring the current
     * {@code omnitools-*} name and falling back to the pre-rename
     * {@code qiandao-*} name.  The returned path is the actual source so the
     * archive manifest preserves which file was migrated.
     */
    private static Path findLegacyConfig(String currentName) {
        Path current = ConfigPaths.oldConfig(currentName);
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

        Path fallback = ConfigPaths.oldConfig(fallbackName);
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

    private static void archive(Path source) throws IOException {
        Path archived = ConfigPaths.legacyDir().resolve(source.getFileName());
        if (!Files.exists(archived)) {
            Files.copy(source, archived, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path manifest = ConfigPaths.legacyDir().resolve("manifest.json");
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
}
