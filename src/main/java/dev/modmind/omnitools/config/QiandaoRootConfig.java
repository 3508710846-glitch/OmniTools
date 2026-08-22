package dev.modmind.omnitools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/** Versioned root configuration and module enablement flags. */
public record QiandaoRootConfig(int formatVersion, boolean debug, String timezone,
                                Map<ModuleId, Boolean> modules) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public QiandaoRootConfig {
        if (formatVersion < 1) {
            throw new JsonParseException("format_version must be a positive integer");
        }
        String zone = timezone == null || timezone.isBlank() ? "UTC" : timezone.trim();
        try {
            ZoneId.of(zone);
        } catch (RuntimeException exception) {
            throw new JsonParseException("global.timezone is not a valid ZoneId: " + zone);
        }
        timezone = zone;
        EnumMap<ModuleId, Boolean> copy = new EnumMap<>(ModuleId.class);
        for (ModuleId module : ModuleId.values()) {
            copy.put(module, modules == null || !modules.containsKey(module) || modules.get(module));
        }
        modules = Map.copyOf(copy);
    }

    public static QiandaoRootConfig defaults() {
        EnumMap<ModuleId, Boolean> modules = new EnumMap<>(ModuleId.class);
        for (ModuleId module : ModuleId.values()) {
            modules.put(module, module != ModuleId.PERMISSIONS);
        }
        return new QiandaoRootConfig(CURRENT_FORMAT_VERSION, false, "Asia/Shanghai", modules);
    }

    public boolean enabled(ModuleId module) {
        return modules.getOrDefault(module, true);
    }

    public static QiandaoRootConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            QiandaoRootConfig defaults = defaults();
            save(path, defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject()) {
                throw new JsonParseException("Root configuration must be an object");
            }
            JsonObject root = element.getAsJsonObject();
            int version = integer(root, "format_version", CURRENT_FORMAT_VERSION);
            JsonObject global = object(root, "global");
            JsonObject moduleObject = object(root, "modules");
            EnumMap<ModuleId, Boolean> modules = new EnumMap<>(ModuleId.class);
            for (ModuleId module : ModuleId.values()) {
                JsonElement value = moduleObject.get(module.id());
                modules.put(module, value == null || !value.isJsonObject()
                        || bool(value.getAsJsonObject(), "enabled", true));
            }
            return new QiandaoRootConfig(version, bool(global, "debug", false),
                    string(global, "timezone", "Asia/Shanghai"), modules);
        }
    }

    public static void save(Path path, QiandaoRootConfig config) throws IOException {
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("format_version", config.formatVersion());
        JsonObject global = new JsonObject();
        global.addProperty("debug", config.debug());
        global.addProperty("timezone", config.timezone());
        root.add("global", global);
        JsonObject modules = new JsonObject();
        for (ModuleId module : ModuleId.values()) {
            JsonObject status = new JsonObject();
            status.addProperty("enabled", config.enabled(module));
            modules.add(module.id(), status);
        }
        root.add("modules", modules);
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(key + " must be a string");
        }
        return element.getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException(key + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be an integer");
        }
        try {
            return Integer.parseInt(element.getAsString());
        } catch (NumberFormatException exception) {
            throw new JsonParseException(key + " must be an integer");
        }
    }
}
