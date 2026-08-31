package dev.modmind.omnitools.skills;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.config.ConfigFieldReporter;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

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

/** Versioned, bounded skill-tree definitions. Player progress is stored separately in SkillTreeData. */
public record SkillTreeConfig(int formatVersion, Settings settings, List<TreeDefinition> trees) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final int REQUIRED_SKILL_COUNT = 4;
    private static final int MAX_TREES = 64;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public SkillTreeConfig {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("Unsupported skills format_version: " + formatVersion);
        }
        settings = settings == null ? Settings.defaults() : settings;
        trees = List.copyOf(trees == null ? List.of() : trees);
        if (trees.size() > MAX_TREES) {
            throw new JsonParseException("skills.trees may contain at most " + MAX_TREES + " entries");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (TreeDefinition tree : trees) {
            if (tree == null || !ids.add(tree.id())) {
                throw new JsonParseException("skills.trees contains a duplicate or invalid id");
            }
        }
    }

    public static SkillTreeConfig empty() {
        return new SkillTreeConfig(CURRENT_FORMAT_VERSION, Settings.defaults(), List.of());
    }

    public static Path path() {
        return ConfigPaths.moduleConfig(ModuleId.SKILLS);
    }

    public static SkillTreeConfig load() {
        Path file = path();
        if (!Files.exists(file)) {
            SkillTreeConfig defaults = defaults();
            try {
                save(defaults);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create skill-tree configuration", exception);
            }
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                throw new JsonParseException("skills configuration must be an object");
            }
            return parse(root.getAsJsonObject());
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Invalid skill-tree configuration", exception);
        }
    }

    public Optional<TreeDefinition> tree(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return trees.stream().filter(tree -> tree.id().equals(normalized)).findFirst();
    }

    public static SkillTreeConfig parse(JsonObject root) {
        ConfigFieldReporter.warnUnknown(root, "skills", Set.of("format_version", "settings", "trees"));
        int version = integer(root, "format_version", CURRENT_FORMAT_VERSION, "skills");
        Settings settings = Settings.parse(object(root, "settings", "skills"));
        JsonArray array = array(root, "trees", "skills");
        List<TreeDefinition> trees = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isJsonObject()) {
                throw new JsonParseException("skills.trees[" + index + "] must be an object");
            }
            trees.add(TreeDefinition.parse(array.get(index).getAsJsonObject(), settings, "skills.trees[" + index + "]"));
        }
        return new SkillTreeConfig(version, settings, trees);
    }

    public static void save(SkillTreeConfig config) throws IOException {
        Path file = path();
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(config.toJson(), writer);
        }
    }

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", formatVersion);
        root.add("settings", settings.toJson());
        JsonArray array = new JsonArray();
        trees.forEach(tree -> array.add(tree.toJson()));
        root.add("trees", array);
        return root;
    }

    private static SkillTreeConfig defaults() {
        Settings settings = Settings.defaults();
        return new SkillTreeConfig(CURRENT_FORMAT_VERSION, settings, List.of(
                tree("gathering", "采集", "minecraft:diamond_pickaxe", SkillAttribute.BLOCK_BREAK_SPEED,
                        SkillXpSource.BLOCK_BREAK, "精准采集", "高效作业", "资源感知", "过载采集"),
                tree("combat", "战斗", "minecraft:iron_sword", SkillAttribute.ATTACK_DAMAGE,
                        SkillXpSource.ENTITY_KILL, "战斗本能", "迅捷攻击", "致命打击", "战意爆发"),
                tree("defense", "防御", "minecraft:shield", SkillAttribute.ARMOR,
                        SkillXpSource.ENTITY_KILL, "稳固姿态", "坚韧护甲", "伤害偏转", "不屈壁垒"),
                tree("hunting", "狩猎", "minecraft:bow", SkillAttribute.LUCK,
                        SkillXpSource.ENTITY_KILL, "追猎直觉", "猎手步伐", "稀有感知", "首领猎杀"),
                tree("crafting", "制造", "minecraft:crafting_table", SkillAttribute.LUCK,
                        SkillXpSource.CRAFT, "工匠基础", "熟练制作", "品质把控", "大师工坊"),
                tree("survival", "生存", "minecraft:golden_apple", SkillAttribute.MAX_HEALTH,
                        SkillXpSource.SURVIVAL, "野外本能", "耐力恢复", "远行准备", "生存专家")));
    }

    private static TreeDefinition tree(String id, String display, String icon, SkillAttribute attribute,
                                       SkillXpSource primarySource, String first, String second,
                                       String third, String fourth) {
        return new TreeDefinition(id, display, icon, item(icon), attribute,
                Set.of(primarySource, SkillXpSource.REWARD, SkillXpSource.COMMAND),
                List.of(new LevelMultiplier(1, 1.0D), new LevelMultiplier(501, 1.25D),
                        new LevelMultiplier(1001, 1.6D), new LevelMultiplier(1501, 2.0D)),
                List.of(new SkillDefinition("foundation", first, "基础技能，达到等级后可解锁。", 1, 0),
                        new SkillDefinition("efficiency", second, "提升该玩法的效率方向。", 250, 1),
                        new SkillDefinition("yield", third, "提升该玩法的收益方向。", 750, 1),
                        new SkillDefinition("ultimate", fourth, "终极技能，须投入技能点解锁。", 1500, 2)));
    }

    public record Settings(int maxLevel, int pointsEveryLevels, long maxDailyXp, int minIntervalTicks,
                           double baseAttributeCap, double pointAttributeCap, double pointAttributeBonus,
                           double maxTitleXpBonus, long xpBase, long xpLinear, double xpQuadratic) {
        public Settings {
            if (maxLevel < 1 || maxLevel > 10_000) throw new JsonParseException("skills.settings.max_level must be 1-10000");
            if (pointsEveryLevels < 1 || pointsEveryLevels > maxLevel) throw new JsonParseException("skills.settings.points_every_levels is invalid");
            if (maxDailyXp < 1L || maxDailyXp > 1_000_000_000L) throw new JsonParseException("skills.settings.max_daily_xp is invalid");
            if (minIntervalTicks < 0 || minIntervalTicks > 1200) throw new JsonParseException("skills.settings.min_interval_ticks is invalid");
            if (!fraction(baseAttributeCap) || !fraction(pointAttributeCap) || !fraction(pointAttributeBonus)
                    || !fraction(maxTitleXpBonus) || !Double.isFinite(xpQuadratic) || xpQuadratic < 0.0D) {
                throw new JsonParseException("skills.settings contains an invalid percentage or XP curve value");
            }
            if (baseAttributeCap + pointAttributeCap > 1.0D + 0.000_001D) throw new JsonParseException("skills attribute cap may not exceed 100%");
            if (pointAttributeBonus > pointAttributeCap) throw new JsonParseException("skills point attribute bonus exceeds its cap");
            if (xpBase < 1L || xpLinear < 0L) throw new JsonParseException("skills XP curve values are invalid");
        }

        static Settings defaults() {
            return new Settings(2000, 500, 250_000L, 4, 0.30D, 0.20D, 0.05D, 0.50D,
                    100L, 25L, 0.015D);
        }

        static Settings parse(JsonObject object) {
            return new Settings(integer(object, "max_level", 2000, "skills.settings"),
                    integer(object, "points_every_levels", 500, "skills.settings"),
                    positiveLong(object, "max_daily_xp", 250_000L, "skills.settings"),
                    integer(object, "min_interval_ticks", 4, "skills.settings"),
                    decimal(object, "base_attribute_cap", 0.30D, "skills.settings"),
                    decimal(object, "point_attribute_cap", 0.20D, "skills.settings"),
                    decimal(object, "point_attribute_bonus", 0.05D, "skills.settings"),
                    decimal(object, "max_title_xp_bonus", 0.50D, "skills.settings"),
                    positiveLong(object, "xp_base", 100L, "skills.settings"),
                    positiveLong(object, "xp_linear", 25L, "skills.settings"),
                    decimal(object, "xp_quadratic", 0.015D, "skills.settings"));
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("max_level", maxLevel);
            object.addProperty("points_every_levels", pointsEveryLevels);
            object.addProperty("max_daily_xp", maxDailyXp);
            object.addProperty("min_interval_ticks", minIntervalTicks);
            object.addProperty("base_attribute_cap", baseAttributeCap);
            object.addProperty("point_attribute_cap", pointAttributeCap);
            object.addProperty("point_attribute_bonus", pointAttributeBonus);
            object.addProperty("max_title_xp_bonus", maxTitleXpBonus);
            object.addProperty("xp_base", xpBase);
            object.addProperty("xp_linear", xpLinear);
            object.addProperty("xp_quadratic", xpQuadratic);
            return object;
        }
    }

    public record TreeDefinition(String id, String display, String iconId, Item icon, SkillAttribute attribute,
                                 Set<SkillXpSource> sources, List<LevelMultiplier> levelMultipliers,
                                 List<SkillDefinition> skills) {
        public TreeDefinition {
            id = normalizedId(id, "tree id");
            if (display == null || display.isBlank() || display.length() > 64) throw new JsonParseException("skill tree display is invalid");
            Identifier identifier = Identifier.tryParse(iconId == null ? "" : iconId);
            if (identifier == null || icon == null) throw new JsonParseException("skill tree " + id + " has an invalid icon");
            attribute = attribute == null ? SkillAttribute.LUCK : attribute;
            sources = Set.copyOf(sources == null ? Set.of() : sources);
            if (sources.isEmpty()) throw new JsonParseException("skill tree " + id + " must whitelist at least one XP source");
            levelMultipliers = List.copyOf(levelMultipliers == null ? List.of() : levelMultipliers);
            if (levelMultipliers.isEmpty() || levelMultipliers.getFirst().fromLevel() != 1) throw new JsonParseException("skill tree " + id + " must begin multipliers at level 1");
            int previous = 0;
            for (LevelMultiplier multiplier : levelMultipliers) {
                if (multiplier.fromLevel() <= previous) throw new JsonParseException("skill tree " + id + " has unordered level multipliers");
                previous = multiplier.fromLevel();
            }
            skills = List.copyOf(skills == null ? List.of() : skills);
            if (skills.size() != REQUIRED_SKILL_COUNT) throw new JsonParseException("skill tree " + id + " must define exactly " + REQUIRED_SKILL_COUNT + " skills");
            Set<String> skillIds = new LinkedHashSet<>();
            for (SkillDefinition skill : skills) if (!skillIds.add(skill.id())) throw new JsonParseException("skill tree " + id + " has duplicate skill ids");
        }

        static TreeDefinition parse(JsonObject object, Settings settings, String context) {
            ConfigFieldReporter.warnUnknown(object, context, Set.of("id", "display", "icon", "attribute", "sources", "level_multipliers", "skills"));
            String id = normalizedId(requiredString(object, "id", context), context + ".id");
            String iconId = requiredString(object, "icon", context);
            Item icon = item(iconId);
            Set<SkillXpSource> sources = new LinkedHashSet<>();
            for (JsonElement source : array(object, "sources", context)) {
                if (!source.isJsonPrimitive() || !source.getAsJsonPrimitive().isString()) throw new JsonParseException(context + ".sources must contain strings");
                sources.add(SkillXpSource.parse(source.getAsString()));
            }
            List<LevelMultiplier> multipliers = new ArrayList<>();
            for (JsonElement entry : array(object, "level_multipliers", context)) {
                if (!entry.isJsonObject()) throw new JsonParseException(context + ".level_multipliers must contain objects");
                multipliers.add(LevelMultiplier.parse(entry.getAsJsonObject(), context + ".level_multipliers"));
            }
            List<SkillDefinition> skills = new ArrayList<>();
            for (JsonElement entry : array(object, "skills", context)) {
                if (!entry.isJsonObject()) throw new JsonParseException(context + ".skills must contain objects");
                skills.add(SkillDefinition.parse(entry.getAsJsonObject(), context + ".skills", settings));
            }
            return new TreeDefinition(id, requiredString(object, "display", context), iconId, icon,
                    SkillAttribute.parse(requiredString(object, "attribute", context)), sources, multipliers, skills);
        }

        public double multiplierForLevel(int level) {
            LevelMultiplier selected = levelMultipliers.getFirst();
            for (LevelMultiplier multiplier : levelMultipliers) {
                if (multiplier.fromLevel() > level) break;
                selected = multiplier;
            }
            return selected.multiplier();
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("id", id);
            object.addProperty("display", display);
            object.addProperty("icon", iconId);
            object.addProperty("attribute", attribute.serializedName());
            JsonArray sourcesArray = new JsonArray();
            sources.forEach(source -> sourcesArray.add(source.serializedName()));
            object.add("sources", sourcesArray);
            JsonArray multipliers = new JsonArray();
            levelMultipliers.forEach(multiplier -> multipliers.add(multiplier.toJson()));
            object.add("level_multipliers", multipliers);
            JsonArray skillsArray = new JsonArray();
            skills.forEach(skill -> skillsArray.add(skill.toJson()));
            object.add("skills", skillsArray);
            return object;
        }
    }

    public record LevelMultiplier(int fromLevel, double multiplier) {
        public LevelMultiplier {
            if (fromLevel < 1 || !Double.isFinite(multiplier) || multiplier <= 0.0D || multiplier > 100.0D) throw new JsonParseException("skill level multiplier is invalid");
        }
        static LevelMultiplier parse(JsonObject object, String context) { return new LevelMultiplier(integer(object, "from_level", -1, context), decimal(object, "multiplier", -1.0D, context)); }
        JsonObject toJson() { JsonObject object = new JsonObject(); object.addProperty("from_level", fromLevel); object.addProperty("multiplier", multiplier); return object; }
    }

    public record SkillDefinition(String id, String display, String description, int unlockLevel, int pointCost) {
        public SkillDefinition {
            id = normalizedId(id, "skill id");
            if (display == null || display.isBlank() || display.length() > 64 || description == null || description.length() > 256) throw new JsonParseException("skill definition text is invalid");
            if (unlockLevel < 1 || pointCost < 0 || pointCost > 4) throw new JsonParseException("skill unlock requirements are invalid");
        }
        static SkillDefinition parse(JsonObject object, String context, Settings settings) {
            ConfigFieldReporter.warnUnknown(object, context, Set.of("id", "display", "description", "unlock_level", "point_cost"));
            SkillDefinition definition = new SkillDefinition(requiredString(object, "id", context), requiredString(object, "display", context),
                    string(object, "description", ""), integer(object, "unlock_level", -1, context), integer(object, "point_cost", 0, context));
            if (definition.unlockLevel() > settings.maxLevel()) throw new JsonParseException(context + ".unlock_level exceeds max_level");
            return definition;
        }
        JsonObject toJson() { JsonObject object = new JsonObject(); object.addProperty("id", id); object.addProperty("display", display); object.addProperty("description", description); object.addProperty("unlock_level", unlockLevel); object.addProperty("point_cost", pointCost); return object; }
    }

    private static Item item(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) throw new JsonParseException("invalid item identifier: " + id);
        return BuiltInRegistries.ITEM.getOptional(identifier).orElseThrow(() -> new JsonParseException("unknown item: " + id));
    }
    private static boolean fraction(double value) { return Double.isFinite(value) && value >= 0.0D && value <= 1.0D; }
    private static JsonObject object(JsonObject root, String key, String context) { JsonElement value = root.get(key); if (value == null || !value.isJsonObject()) throw new JsonParseException(context + "." + key + " must be an object"); return value.getAsJsonObject(); }
    private static JsonArray array(JsonObject root, String key, String context) { JsonElement value = root.get(key); if (value == null || !value.isJsonArray()) throw new JsonParseException(context + "." + key + " must be an array"); return value.getAsJsonArray(); }
    private static String requiredString(JsonObject object, String key, String context) { String value = string(object, key, ""); if (value.isBlank()) throw new JsonParseException(context + "." + key + " must be a non-empty string"); return value.trim(); }
    private static String string(JsonObject object, String key, String fallback) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new JsonParseException(key + " must be a string"); return value.getAsString(); }
    private static int integer(JsonObject object, String key, int fallback, String context) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException(context + "." + key + " must be an integer"); try { return Integer.parseInt(value.getAsString()); } catch (NumberFormatException exception) { throw new JsonParseException(context + "." + key + " must be an integer"); } }
    private static long positiveLong(JsonObject object, String key, long fallback, String context) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException(context + "." + key + " must be a positive integer"); try { long result = Long.parseLong(value.getAsString()); if (result < 1L) throw new NumberFormatException(); return result; } catch (NumberFormatException exception) { throw new JsonParseException(context + "." + key + " must be a positive integer"); } }
    private static double decimal(JsonObject object, String key, double fallback, String context) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException(context + "." + key + " must be a number"); double result = value.getAsDouble(); if (!Double.isFinite(result)) throw new JsonParseException(context + "." + key + " must be finite"); return result; }
    private static String normalizedId(String value, String context) { String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT); if (!ID_PATTERN.matcher(id).matches()) throw new JsonParseException(context + " must match " + ID_PATTERN.pattern()); return id; }
}
