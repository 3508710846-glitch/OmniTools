package dev.modmind.omnitools.sidebar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.LegacyTitleText;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Administrator-editable sidebar presentation configuration. */
public record SidebarConfig(int formatVersion, boolean defaultVisible, int refreshIntervalTicks,
                            String title, List<SidebarLine> lines, ConflictPolicy conflictPolicy) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final int MAX_LINES = 15;
    public static final int MIN_REFRESH_INTERVAL_TICKS = 5;
    public static final int MAX_REFRESH_INTERVAL_TICKS = 600;
    private static final int MAX_TITLE_LENGTH = 64;
    private static final int MAX_LINE_LENGTH = 256;
    private static final Pattern LINE_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.SIDEBAR);

    public SidebarConfig {
        if (formatVersion < 1) {
            throw new JsonParseException("sidebar.format_version must be a positive integer");
        }
        if (refreshIntervalTicks < MIN_REFRESH_INTERVAL_TICKS || refreshIntervalTicks > MAX_REFRESH_INTERVAL_TICKS) {
            throw new JsonParseException("sidebar.refresh_interval_ticks must be between 5 and 600");
        }
        title = title == null ? "" : title;
        if (title.length() > MAX_TITLE_LENGTH || LegacyTitleText.plainText(colored(title)).length() > MAX_TITLE_LENGTH) {
            throw new JsonParseException("sidebar.title is too long");
        }
        if (lines == null || lines.size() > MAX_LINES) {
            throw new JsonParseException("sidebar.lines may contain at most " + MAX_LINES + " entries");
        }
        Set<String> ids = new HashSet<>();
        List<SidebarLine> copy = new ArrayList<>();
        for (SidebarLine line : lines) {
            if (line == null || line.id() == null || !LINE_ID.matcher(line.id()).matches()
                    || !ids.add(line.id().toLowerCase(Locale.ROOT))) {
                throw new JsonParseException("sidebar line ids must be unique and match " + LINE_ID.pattern());
            }
            if (line.text() == null || line.text().isBlank() || line.text().length() > MAX_LINE_LENGTH) {
                throw new JsonParseException("sidebar line " + line.id() + " must contain non-empty text");
            }
            copy.add(new SidebarLine(line.id(), line.text()));
        }
        lines = List.copyOf(copy);
        conflictPolicy = conflictPolicy == null ? ConflictPolicy.WARN : conflictPolicy;
    }

    public static SidebarConfig empty() {
        return new SidebarConfig(CURRENT_FORMAT_VERSION, false, 20, "", List.of(), ConflictPolicy.WARN);
    }

    public static SidebarConfig load() {
        if (!Files.exists(FILE)) {
            SidebarConfig defaults = defaults();
            try {
                save(defaults);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create " + FILE, exception);
            }
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject()) {
                throw new JsonParseException("sidebar configuration must be an object");
            }
            return parse(element.getAsJsonObject());
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            System.err.println("[omnitools] Could not load " + FILE + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid sidebar configuration", exception);
        }
    }

    public static Path path() {
        return FILE;
    }

    private static SidebarConfig defaults() {
        return new SidebarConfig(CURRENT_FORMAT_VERSION, true, 20, "&b&lOmniTools", List.of(
                new SidebarLine("player", "&f玩家：&b%omnitools:title_plain%"),
                new SidebarLine("balance", "&e货币：&f%omnitools:balance_formatted%"),
                new SidebarLine("checkin", "&a签到天数：&f%omnitools:checkin_total_days%"),
                new SidebarLine("streak", "&6连续签到：&f%omnitools:checkin_streak_days%"),
                new SidebarLine("online", "&d今日在线：&f%omnitools:online_today_hms%"),
                new SidebarLine("achievement", "&b成就：&f%omnitools:achievements_unlocked%/%omnitools:achievements_total%")
        ), ConflictPolicy.WARN);
    }

    private static SidebarConfig parse(JsonObject root) {
        int version = integer(root, "format_version", CURRENT_FORMAT_VERSION);
        boolean visible = bool(root, "default_visible", true);
        int interval = integer(root, "refresh_interval_ticks", 20);
        String title = string(root, "title", "");
        List<SidebarLine> lines = new ArrayList<>();
        JsonElement linesElement = root.get("lines");
        if (linesElement == null || !linesElement.isJsonArray()) {
            throw new JsonParseException("sidebar.lines must be an array");
        }
        JsonArray array = linesElement.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonObject()) {
                throw new JsonParseException("sidebar.lines[" + index + "] must be an object");
            }
            JsonObject line = element.getAsJsonObject();
            lines.add(new SidebarLine(string(line, "id", ""), string(line, "text", "")));
        }
        String policy = string(root, "conflict_policy", "warn");
        return new SidebarConfig(version, visible, interval, title, lines, ConflictPolicy.parse(policy));
    }

    private static void save(SidebarConfig config) throws IOException {
        Files.createDirectories(FILE.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("format_version", config.formatVersion());
        root.addProperty("default_visible", config.defaultVisible());
        root.addProperty("refresh_interval_ticks", config.refreshIntervalTicks());
        root.addProperty("title", config.title());
        root.addProperty("conflict_policy", config.conflictPolicy().serializedName());
        JsonArray lines = new JsonArray();
        config.lines().forEach(line -> {
            JsonObject value = new JsonObject();
            value.addProperty("id", line.id());
            value.addProperty("text", line.text());
            lines.add(value);
        });
        root.add("lines", lines);
        try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static String colored(String value) {
        return value.replace('&', '\u00a7');
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("sidebar." + key + " must be a string");
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException("sidebar." + key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("sidebar." + key + " must be an integer");
        }
        try {
            return Integer.parseInt(value.getAsString());
        } catch (NumberFormatException exception) {
            throw new JsonParseException("sidebar." + key + " must be an integer");
        }
    }

    public enum ConflictPolicy {
        WARN("warn"), REPLACE("replace"), DISABLED("disabled");

        private final String serializedName;

        ConflictPolicy(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        static ConflictPolicy parse(String value) {
            for (ConflictPolicy policy : values()) {
                if (policy.serializedName.equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return policy;
                }
            }
            throw new JsonParseException("sidebar.conflict_policy must be warn, replace, or disabled");
        }
    }
}
