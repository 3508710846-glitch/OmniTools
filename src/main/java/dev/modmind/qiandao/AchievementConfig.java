package dev.modmind.qiandao;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import dev.modmind.qiandao.config.ConfigPaths;
import dev.modmind.qiandao.config.ModuleId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Server-side definitions for custom achievements backed by vanilla statistics. */
public final class AchievementConfig {
    public static final String FILE_NAME = "qiandao-achievements.json";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final int MAX_DISPLAY_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 512;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.ACHIEVEMENTS);

    private final List<AchievementDefinition> achievements;
    private final Map<String, AchievementDefinition> byId;

    private AchievementConfig(List<AchievementDefinition> achievements) {
        this.achievements = List.copyOf(achievements);
        Map<String, AchievementDefinition> indexed = new LinkedHashMap<>();
        for (AchievementDefinition achievement : achievements) {
            indexed.put(achievement.id(), achievement);
        }
        this.byId = Collections.unmodifiableMap(indexed);
    }

    public static AchievementConfig load() {
        if (!Files.exists(FILE)) {
            AchievementConfig defaults = defaults();
            write(defaults);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                throw new JsonParseException("Root value must be an object");
            }
            return parse(root.getAsJsonObject());
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            System.err.println("[qiandao] Could not load " + FILE + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid achievement configuration", exception);
        }
    }

    public static AchievementConfig empty() {
        return new AchievementConfig(List.of());
    }

    public static Path path() {
        return FILE;
    }

    public List<AchievementDefinition> achievements() {
        return achievements;
    }

    public Optional<AchievementDefinition> definition(String id) {
        return Optional.ofNullable(byId.get(normalizeId(id)));
    }

    private static AchievementConfig parse(JsonObject root) {
        JsonElement element = root.get("achievements");
        if (element == null || !element.isJsonArray()) {
            throw new JsonParseException("achievements must be an array");
        }

        JsonArray array = element.getAsJsonArray();
        List<AchievementDefinition> definitions = new ArrayList<>();
        Map<String, Boolean> ids = new LinkedHashMap<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement achievementElement = array.get(index);
            if (!achievementElement.isJsonObject()) {
                throw new JsonParseException("Achievement entry " + index + " must be an object");
            }
            JsonObject achievement = achievementElement.getAsJsonObject();
            String id = normalizeId(requiredString(achievement, "id"));
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new JsonParseException("Achievement id " + id + " must match " + ID_PATTERN.pattern());
            }
            if (ids.put(id, Boolean.TRUE) != null) {
                throw new JsonParseException("Achievement id " + id + " is configured more than once");
            }

            String display = requiredString(achievement, "display");
            if (display.length() > MAX_DISPLAY_LENGTH || LegacyTitleText.plainText(display).isBlank()) {
                throw new JsonParseException("Achievement display for " + id
                        + " must contain visible text and be at most " + MAX_DISPLAY_LENGTH + " characters");
            }
            String description = requiredString(achievement, "description");
            if (description.length() > MAX_DESCRIPTION_LENGTH) {
                throw new JsonParseException("Achievement description for " + id + " is too long");
            }

            String iconId = requiredString(achievement, "icon");
            Item icon = resolveItem(iconId, "icon for achievement " + id);
            if (icon == net.minecraft.world.item.Items.AIR) {
                throw new JsonParseException("Achievement " + id + " cannot use minecraft:air as an icon");
            }

            JsonElement requirementsElement = achievement.get("requirements");
            if (requirementsElement == null || !requirementsElement.isJsonArray()
                    || requirementsElement.getAsJsonArray().isEmpty()) {
                throw new JsonParseException("Achievement " + id + " must contain at least one requirement");
            }
            List<Requirement> requirements = parseRequirements(id, requirementsElement.getAsJsonArray());
            Reward reward = parseReward(achievement.get("rewards"), id);
            definitions.add(new AchievementDefinition(id, display, description, iconId, icon, requirements, reward));
        }
        return new AchievementConfig(definitions);
    }

    private static List<Requirement> parseRequirements(String achievementId, JsonArray array) {
        List<Requirement> requirements = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonObject()) {
                throw new JsonParseException("Requirement " + achievementId + "[" + index + "] must be an object");
            }
            JsonObject requirement = element.getAsJsonObject();
            RequirementType type = RequirementType.parse(requiredString(requirement, "type"));
            String targetId = requiredString(requirement, "target");
            long count = positiveLong(requirement, "count");
            if (type == RequirementType.BLOCK_MINED) {
                Block block = resolveBlock(targetId, "block target for achievement " + achievementId);
                requirements.add(new Requirement(type, targetId, count, block, null));
            } else {
                EntityType<?> entityType = resolveEntityType(targetId,
                        "entity target for achievement " + achievementId);
                requirements.add(new Requirement(type, targetId, count, null, entityType));
            }
        }
        return List.copyOf(requirements);
    }

    private static Reward parseReward(JsonElement element, String achievementId) {
        if (element == null) {
            return new Reward(0L, List.of());
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException("rewards for achievement " + achievementId + " must be an object");
        }
        JsonObject reward = element.getAsJsonObject();
        long coins = nonNegativeLong(reward, "coins", 0L);
        List<String> titles = new ArrayList<>();
        JsonElement titlesElement = reward.get("titles");
        if (titlesElement != null) {
            if (!titlesElement.isJsonArray()) {
                throw new JsonParseException("titles for achievement " + achievementId + " must be an array");
            }
            for (JsonElement titleElement : titlesElement.getAsJsonArray()) {
                if (!titleElement.isJsonPrimitive() || !titleElement.getAsJsonPrimitive().isString()) {
                    throw new JsonParseException("titles for achievement " + achievementId + " must contain strings");
                }
                String titleId = normalizeId(titleElement.getAsString());
                if (!ID_PATTERN.matcher(titleId).matches()) {
                    throw new JsonParseException("Title id " + titleId + " in achievement " + achievementId
                            + " must match " + ID_PATTERN.pattern());
                }
                if (!titles.contains(titleId)) {
                    titles.add(titleId);
                }
            }
        }
        return new Reward(coins, titles);
    }

    private static AchievementConfig defaults() {
        Identifier stoneId = Identifier.withDefaultNamespace("stone");
        Item icon = resolveItem("minecraft:stone", "default achievement icon");
        Block stone = resolveBlock("minecraft:stone", "default achievement target");
        Requirement requirement = new Requirement(RequirementType.BLOCK_MINED, stoneId.toString(), 1000L, stone, null);
        AchievementDefinition definition = new AchievementDefinition(
                "stone_breaker", "\u77f3\u5320", "\u6316\u6398\u77f3\u5934 1000 \u4e2a", "minecraft:stone", icon,
                List.of(requirement), new Reward(500L, List.of("geologist")));
        return new AchievementConfig(List.of(definition));
    }

    private static void write(AchievementConfig config) {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", 1);
            JsonArray achievements = new JsonArray();
            for (AchievementDefinition definition : config.achievements) {
                JsonObject achievement = new JsonObject();
                achievement.addProperty("id", definition.id());
                achievement.addProperty("display", definition.display());
                achievement.addProperty("description", definition.description());
                achievement.addProperty("icon", definition.iconId());
                JsonArray requirements = new JsonArray();
                for (Requirement requirement : definition.requirements()) {
                    JsonObject requirementObject = new JsonObject();
                    requirementObject.addProperty("type", requirement.type().serializedName());
                    requirementObject.addProperty("target", requirement.targetId());
                    requirementObject.addProperty("count", requirement.count());
                    requirements.add(requirementObject);
                }
                achievement.add("requirements", requirements);
                JsonObject rewards = new JsonObject();
                rewards.addProperty("coins", definition.rewards().coins());
                JsonArray titles = new JsonArray();
                definition.rewards().titles().forEach(titles::add);
                rewards.add("titles", titles);
                achievement.add("rewards", rewards);
                achievements.add(achievement);
            }
            root.add("achievements", achievements);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            System.out.println("[qiandao] Created default achievement config at " + FILE);
        } catch (IOException exception) {
            System.err.println("[qiandao] Could not create default achievement config at " + FILE + ": "
                    + exception.getMessage());
        }
    }

    private static Item resolveItem(String value, String context) {
        Identifier id = parseIdentifier(value, context);
        return BuiltInRegistries.ITEM.get(id)
                .map(holder -> holder.value())
                .orElseThrow(() -> new JsonParseException("Unknown item " + id + " for " + context));
    }

    private static Block resolveBlock(String value, String context) {
        Identifier id = parseIdentifier(value, context);
        return BuiltInRegistries.BLOCK.get(id)
                .map(holder -> holder.value())
                .orElseThrow(() -> new JsonParseException("Unknown block " + id + " for " + context));
    }

    private static EntityType<?> resolveEntityType(String value, String context) {
        Identifier id = parseIdentifier(value, context);
        return BuiltInRegistries.ENTITY_TYPE.get(id)
                .map(holder -> holder.value())
                .orElseThrow(() -> new JsonParseException("Unknown entity type " + id + " for " + context));
    }

    private static Identifier parseIdentifier(String value, String context) {
        Identifier id = Identifier.tryParse(value);
        if (id == null) {
            throw new JsonParseException("Invalid identifier " + value + " for " + context);
        }
        return id;
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new JsonParseException(key + " must be a non-empty string");
        }
        return element.getAsString().trim();
    }

    private static long positiveLong(JsonObject object, String key) {
        long value = nonNegativeLong(object, key, -1L);
        if (value < 1L) {
            throw new JsonParseException(key + " must be a positive integer");
        }
        return value;
    }

    private static long nonNegativeLong(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            if (fallback >= 0L) {
                return fallback;
            }
            throw new JsonParseException(key + " must be a non-negative integer");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be a non-negative integer");
        }
        try {
            long value = Long.parseLong(element.getAsString());
            if (value < 0L) {
                throw new JsonParseException(key + " must be a non-negative integer");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new JsonParseException(key + " must be a non-negative integer");
        }
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public enum RequirementType {
        BLOCK_MINED("block_mined", "gui.qiandao.achievement.requirement.block_mined"),
        ENTITY_KILLED("entity_killed", "gui.qiandao.achievement.requirement.entity_killed");

        private final String serializedName;
        private final String translationKey;

        RequirementType(String serializedName, String translationKey) {
            this.serializedName = serializedName;
            this.translationKey = translationKey;
        }

        public String serializedName() {
            return serializedName;
        }

        public String translationKey() {
            return translationKey;
        }

        static RequirementType parse(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (RequirementType type : values()) {
                if (type.serializedName.equals(normalized)) {
                    return type;
                }
            }
            throw new JsonParseException("Unknown achievement requirement type: " + value);
        }
    }

    public record Requirement(RequirementType type, String targetId, long count, Block blockTarget,
                              EntityType<?> entityTarget) {
        public long current(ServerPlayer player) {
            return switch (type) {
                case BLOCK_MINED -> player.getStats().getValue(Stats.BLOCK_MINED.get(blockTarget));
                case ENTITY_KILLED -> player.getStats().getValue(Stats.ENTITY_KILLED.get(entityTarget));
            };
        }
    }

    public record Reward(long coins, List<String> titles) {
        public Reward {
            titles = List.copyOf(titles == null ? List.of() : titles);
        }
    }

    public record AchievementDefinition(String id, String display, String description, String iconId, Item icon,
                                        List<Requirement> requirements, Reward rewards) {
        public AchievementDefinition {
            requirements = List.copyOf(requirements);
        }

        public boolean complete(ServerPlayer player) {
            return requirements.stream().allMatch(requirement -> requirement.current(player) >= requirement.count());
        }
    }
}
