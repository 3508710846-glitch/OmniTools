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
public record OmniToolsRootConfig(int formatVersion, boolean debug, String timezone,
                                  String language,
                                  boolean allowCommandRewards, int maxCommandRewardLength,
                                  boolean placeholderApiEnabled, DataRetention dataRetention,
                                  CommandSecurityConfig commandSecurity,
                                  Map<ModuleId, Boolean> modules) {
    public static final int CURRENT_FORMAT_VERSION = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public OmniToolsRootConfig {
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
        language = normalizeLanguage(language);
        dataRetention = dataRetention == null ? DataRetention.FULL : dataRetention;
        commandSecurity = commandSecurity == null ? CommandSecurityConfig.defaults() : commandSecurity;
        if (maxCommandRewardLength < 1 || maxCommandRewardLength > 16_384) {
            throw new JsonParseException("global.reward_security.max_command_length must be between 1 and 16384");
        }
        EnumMap<ModuleId, Boolean> copy = new EnumMap<>(ModuleId.class);
        for (ModuleId module : ModuleId.values()) {
            copy.put(module, modules == null || !modules.containsKey(module)
                    ? module != ModuleId.PERMISSIONS
                    : modules.get(module));
        }
        modules = Map.copyOf(copy);
    }

    public static OmniToolsRootConfig defaults() {
        EnumMap<ModuleId, Boolean> modules = new EnumMap<>(ModuleId.class);
        for (ModuleId module : ModuleId.values()) {
            modules.put(module, module != ModuleId.PERMISSIONS);
        }
        return new OmniToolsRootConfig(CURRENT_FORMAT_VERSION, false, "Asia/Shanghai", "zh_cn",
                false, 1_024, true, DataRetention.FULL, CommandSecurityConfig.defaults(), modules);
    }

    public boolean enabled(ModuleId module) {
        return modules.getOrDefault(module, true);
    }

    /** Returns a copy with one module switch changed without touching disk. */
    public OmniToolsRootConfig withModuleEnabled(ModuleId module, boolean enabled) {
        if (module == null) {
            throw new IllegalArgumentException("module cannot be null");
        }
        EnumMap<ModuleId, Boolean> updated = new EnumMap<>(ModuleId.class);
        updated.putAll(modules);
        updated.put(module, enabled);
        return new OmniToolsRootConfig(formatVersion, debug, timezone, language, allowCommandRewards,
                maxCommandRewardLength, placeholderApiEnabled, dataRetention, commandSecurity, updated);
    }

    public static OmniToolsRootConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            OmniToolsRootConfig defaults = defaults();
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
            JsonObject rewardSecurity = object(global, "reward_security");
            JsonObject commandSecurity = object(global, "command_security");
            JsonObject integrations = object(root, "integrations");
            JsonObject placeholderApi = object(integrations, "placeholder_api");
            JsonObject moduleObject = object(root, "modules");
            EnumMap<ModuleId, Boolean> modules = new EnumMap<>(ModuleId.class);
            for (ModuleId module : ModuleId.values()) {
                JsonElement value = moduleObject.get(module.id());
                modules.put(module, value == null || !value.isJsonObject()
                        || bool(value.getAsJsonObject(), "enabled", true));
            }
            return new OmniToolsRootConfig(version, bool(global, "debug", false),
                    string(global, "timezone", "Asia/Shanghai"),
                    string(global, "language", "zh_cn"),
                    bool(rewardSecurity, "allow_command_rewards", false),
                    integer(rewardSecurity, "max_command_length", 1_024),
                    bool(placeholderApi, "enabled", true),
                    DataRetention.parse(string(global, "data_retention", "full")),
                    commandSecurity(commandSecurity), modules);
        }
    }

    public static void save(Path path, OmniToolsRootConfig config) throws IOException {
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("format_version", config.formatVersion());
        JsonObject global = new JsonObject();
        global.addProperty("debug", config.debug());
        global.addProperty("timezone", config.timezone());
        global.addProperty("language", config.language());
        global.addProperty("data_retention", config.dataRetention().serializedName());
        JsonObject commandSecurity = new JsonObject();
        com.google.gson.JsonArray allowedRoots = new com.google.gson.JsonArray();
        config.commandSecurity().allowedRoots().forEach(allowedRoots::add);
        commandSecurity.add("allowed_roots", allowedRoots);
        commandSecurity.addProperty("max_command_length", config.commandSecurity().maxCommandLength());
        commandSecurity.addProperty("cooldown_ticks", config.commandSecurity().cooldownTicks());
        global.add("command_security", commandSecurity);
        JsonObject rewardSecurity = new JsonObject();
        rewardSecurity.addProperty("allow_command_rewards", config.allowCommandRewards());
        rewardSecurity.addProperty("max_command_length", config.maxCommandRewardLength());
        global.add("reward_security", rewardSecurity);
        root.add("global", global);
        JsonObject integrations = new JsonObject();
        JsonObject placeholderApi = new JsonObject();
        placeholderApi.addProperty("enabled", config.placeholderApiEnabled());
        integrations.add("placeholder_api", placeholderApi);
        root.add("integrations", integrations);
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

    private static CommandSecurityConfig commandSecurity(JsonObject object) {
        com.google.gson.JsonArray roots = object.has("allowed_roots") && object.get("allowed_roots").isJsonArray()
                ? object.getAsJsonArray("allowed_roots") : new com.google.gson.JsonArray();
        java.util.List<String> values = new java.util.ArrayList<>();
        for (JsonElement element : roots) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new JsonParseException("global.command_security.allowed_roots must contain strings");
            }
            values.add(element.getAsString());
        }
        return new CommandSecurityConfig(values, integer(object, "max_command_length", 1_024),
                integer(object, "cooldown_ticks", 0));
    }

    private static String normalizeLanguage(String value) {
        String normalized = value == null ? "zh_cn" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("zh_cn") && !normalized.equals("en_us")) {
            throw new JsonParseException("global.language must be zh_cn or en_us");
        }
        return normalized;
    }

    public enum DataRetention {
        FULL("full"),
        MONTHLY_SUMMARY("monthly_summary"),
        ARCHIVE("archive");

        private final String serializedName;

        DataRetention(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        static DataRetention parse(String value) {
            String normalized = value == null ? "full" : value.trim().toLowerCase(java.util.Locale.ROOT);
            for (DataRetention mode : values()) {
                if (mode.serializedName.equals(normalized)) {
                    return mode;
                }
            }
            throw new JsonParseException("global.data_retention must be full, monthly_summary or archive");
        }
    }
}
