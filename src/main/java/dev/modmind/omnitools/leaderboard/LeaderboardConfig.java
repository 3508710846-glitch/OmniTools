package dev.modmind.omnitools.leaderboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.AchievementConfig;
import dev.modmind.omnitools.config.ConfigFieldReporter;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.statistics.StatisticQuery;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, validated configuration for cached vanilla-statistic leaderboards. */
public record LeaderboardConfig(int formatVersion, int refreshIntervalTicks, boolean includeOfflinePlayers,
                                boolean excludeZeroScores, int maxFilesPerTick,
                                Map<String, List<String>> targetGroups,
                                List<LeaderboardDefinition> leaderboards) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final int MIN_REFRESH_INTERVAL_TICKS = 20;
    public static final int MAX_REFRESH_INTERVAL_TICKS = 72_000;
    public static final int MIN_FILES_PER_TICK = 1;
    public static final int MAX_FILES_PER_TICK = 64;
    public static final int MAX_LEADERBOARDS = 128;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public LeaderboardConfig {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("Unsupported leaderboards format_version: " + formatVersion);
        }
        if (refreshIntervalTicks < MIN_REFRESH_INTERVAL_TICKS || refreshIntervalTicks > MAX_REFRESH_INTERVAL_TICKS) {
            throw new JsonParseException("leaderboards.refresh_interval_ticks must be between "
                    + MIN_REFRESH_INTERVAL_TICKS + " and " + MAX_REFRESH_INTERVAL_TICKS);
        }
        if (maxFilesPerTick < MIN_FILES_PER_TICK || maxFilesPerTick > MAX_FILES_PER_TICK) {
            throw new JsonParseException("leaderboards.max_files_per_tick must be between "
                    + MIN_FILES_PER_TICK + " and " + MAX_FILES_PER_TICK);
        }
        targetGroups = freezeGroups(targetGroups);
        List<LeaderboardDefinition> copy = List.copyOf(leaderboards == null ? List.of() : leaderboards);
        if (copy.size() > MAX_LEADERBOARDS) {
            throw new JsonParseException("leaderboards may contain at most " + MAX_LEADERBOARDS + " entries");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (LeaderboardDefinition board : copy) {
            if (board == null || !ids.add(board.id())) {
                throw new JsonParseException("leaderboards contains a null or duplicate id");
            }
        }
        leaderboards = copy;
    }

    public static LeaderboardConfig empty() {
        return new LeaderboardConfig(CURRENT_FORMAT_VERSION, 200, true, true, 8, Map.of(), List.of());
    }

    public static LeaderboardConfig load() {
        Path file = path();
        if (!Files.exists(file)) {
            LeaderboardConfig defaults = defaults();
            try {
                save(defaults);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create leaderboard configuration", exception);
            }
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject()) {
                throw new JsonParseException("leaderboards configuration must be an object");
            }
            return parse(element.getAsJsonObject());
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Invalid leaderboards configuration", exception);
        }
    }

    public static Path path() {
        return ConfigPaths.moduleConfig(ModuleId.LEADERBOARDS);
    }

    public Optional<LeaderboardDefinition> definition(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return leaderboards.stream().filter(board -> board.id().equals(id.trim().toLowerCase(Locale.ROOT))).findFirst();
    }

    static LeaderboardConfig parse(JsonObject root) {
        ConfigFieldReporter.warnUnknown(root, "leaderboards", Set.of("format_version", "refresh_interval_ticks",
                "include_offline_players", "exclude_zero_scores", "max_files_per_tick", "target_groups",
                "leaderboards"));
        int version = integer(root, "format_version", CURRENT_FORMAT_VERSION);
        int interval = integer(root, "refresh_interval_ticks", 200);
        boolean includeOffline = bool(root, "include_offline_players", true);
        boolean excludeZeros = bool(root, "exclude_zero_scores", true);
        int filesPerTick = integer(root, "max_files_per_tick", 8);
        Map<String, List<String>> groups = parseGroups(root.get("target_groups"));
        JsonElement boardsElement = root.get("leaderboards");
        if (boardsElement == null || !boardsElement.isJsonArray()) {
            throw new JsonParseException("leaderboards.leaderboards must be an array");
        }
        List<LeaderboardDefinition> boards = new ArrayList<>();
        JsonArray array = boardsElement.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isJsonObject()) {
                throw new JsonParseException("leaderboards[" + index + "] must be an object");
            }
            boards.add(LeaderboardDefinition.parse(array.get(index).getAsJsonObject(), groups, "leaderboards[" + index + "]"));
        }
        return new LeaderboardConfig(version, interval, includeOffline, excludeZeros, filesPerTick, groups, boards);
    }

    private static LeaderboardConfig defaults() {
        LeaderboardDefinition board = new LeaderboardDefinition("mine_stone", "&b石材矿工", "累计挖掘石头",
                "minecraft:stone", Items.STONE,
                StatisticQuery.parse(stat("block_mined", List.of("minecraft:stone"), "sum", "count"), Map.of(),
                        "leaderboards[0].stat"), "");
        return new LeaderboardConfig(CURRENT_FORMAT_VERSION, 200, true, true, 8, Map.of(), List.of(board));
    }

    private static JsonObject stat(String type, List<String> targets, String aggregation, String unit) {
        JsonObject value = new JsonObject();
        value.addProperty("type", type);
        JsonArray targetArray = new JsonArray();
        targets.forEach(targetArray::add);
        value.add("targets", targetArray);
        value.addProperty("aggregation", aggregation);
        value.addProperty("unit", unit);
        return value;
    }

    private static void save(LeaderboardConfig config) throws IOException {
        Path file = path();
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("format_version", config.formatVersion());
        root.addProperty("refresh_interval_ticks", config.refreshIntervalTicks());
        root.addProperty("include_offline_players", config.includeOfflinePlayers());
        root.addProperty("exclude_zero_scores", config.excludeZeroScores());
        root.addProperty("max_files_per_tick", config.maxFilesPerTick());
        JsonObject groups = new JsonObject();
        config.targetGroups().forEach((id, values) -> {
            JsonArray items = new JsonArray();
            values.forEach(items::add);
            groups.add(id, items);
        });
        root.add("target_groups", groups);
        JsonArray boards = new JsonArray();
        config.leaderboards().forEach(board -> boards.add(board.toJson()));
        root.add("leaderboards", boards);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static Map<String, List<String>> parseGroups(JsonElement element) {
        if (element == null) {
            return Map.of();
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException("leaderboards.target_groups must be an object");
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String id = normalizeId(entry.getKey(), "target group");
            if (!entry.getValue().isJsonArray() || entry.getValue().getAsJsonArray().isEmpty()) {
                throw new JsonParseException("target group " + id + " must be a non-empty array");
            }
            List<String> values = new ArrayList<>();
            for (JsonElement target : entry.getValue().getAsJsonArray()) {
                if (!target.isJsonPrimitive() || !target.getAsJsonPrimitive().isString()
                        || target.getAsString().isBlank()) {
                    throw new JsonParseException("target group " + id + " must contain non-empty strings");
                }
                values.add(target.getAsString().trim());
            }
            if (groups.put(id, List.copyOf(values)) != null) {
                throw new JsonParseException("Duplicate target group " + id);
            }
        }
        return Map.copyOf(groups);
    }

    private static Map<String, List<String>> freezeGroups(Map<String, List<String>> groups) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (groups != null) {
            groups.forEach((id, values) -> copy.put(normalizeId(id, "target group"),
                    List.copyOf(values == null ? List.of() : values)));
        }
        return Map.copyOf(copy);
    }

    private static String normalizeId(String value, String label) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new JsonParseException("Invalid " + label + " id: " + value);
        }
        return id;
    }

    private static String requiredString(JsonObject object, String key, String context) {
        String value = string(object, key, "");
        if (value.isBlank()) {
            throw new JsonParseException(context + "." + key + " must be a non-empty string");
        }
        return value.trim();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException(key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be an integer");
        }
        try {
            return Integer.parseInt(value.getAsString());
        } catch (NumberFormatException exception) {
            throw new JsonParseException(key + " must be an integer");
        }
    }

    public record LeaderboardDefinition(String id, String display, String description, String iconId, Item icon,
                                        StatisticQuery stat, String linkedAchievement) {
        public LeaderboardDefinition {
            id = normalizeId(id, "leaderboard");
            display = display == null || display.isBlank() ? id : display.trim();
            description = description == null ? "" : description.trim();
            if (display.length() > 128 || description.length() > 256) {
                throw new JsonParseException("Leaderboard display or description is too long");
            }
            iconId = iconId == null ? "" : iconId.trim();
            if (icon == null || icon == Items.AIR) {
                throw new JsonParseException("Leaderboard " + id + " needs a valid icon");
            }
            if (stat == null) {
                throw new JsonParseException("Leaderboard " + id + " needs a stat query");
            }
            linkedAchievement = linkedAchievement == null ? "" : linkedAchievement.trim().toLowerCase(Locale.ROOT);
            if (!linkedAchievement.isEmpty() && !ID.matcher(linkedAchievement).matches()) {
                throw new JsonParseException("Invalid linked_achievement for leaderboard " + id);
            }
        }

        private static LeaderboardDefinition parse(JsonObject value, Map<String, List<String>> groups, String context) {
            ConfigFieldReporter.warnUnknown(value, context,
                    Set.of("id", "display", "description", "icon", "stat", "linked_achievement"));
            String id = requiredString(value, "id", context);
            String iconId = requiredString(value, "icon", context);
            Identifier parsedIcon = Identifier.tryParse(iconId);
            Item icon = parsedIcon == null ? null : BuiltInRegistries.ITEM.getOptional(parsedIcon).orElse(null);
            JsonElement statElement = value.get("stat");
            if (statElement == null || !statElement.isJsonObject()) {
                throw new JsonParseException(context + ".stat must be an object");
            }
            return new LeaderboardDefinition(id, string(value, "display", id), string(value, "description", ""),
                    iconId, icon, StatisticQuery.parse(statElement.getAsJsonObject(), groups, context + ".stat"),
                    string(value, "linked_achievement", ""));
        }

        private JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("id", id);
            value.addProperty("display", display);
            if (!description.isBlank()) {
                value.addProperty("description", description);
            }
            value.addProperty("icon", iconId);
            JsonObject statObject = new JsonObject();
            statObject.addProperty("type", stat.type().serializedName());
            if (stat.type() == AchievementConfig.RequirementType.CUSTOM) {
                statObject.addProperty("custom_stat", stat.targets().getFirst());
            } else {
                JsonArray targets = new JsonArray();
                stat.targets().forEach(targets::add);
                statObject.add("targets", targets);
            }
            statObject.addProperty("aggregation", stat.aggregation().name().toLowerCase(Locale.ROOT));
            statObject.addProperty("unit", stat.unit());
            value.add("stat", statObject);
            if (!linkedAchievement.isBlank()) {
                value.addProperty("linked_achievement", linkedAchievement);
            }
            return value;
        }
    }
}
