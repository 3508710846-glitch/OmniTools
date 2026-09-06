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
    /** Current progression rules. Legacy 2000/500 configurations remain readable. */
    public static final int REQUIRED_SKILL_COUNT = 2;
    public static final int MAX_LEVEL = 100;
    public static final int POINTS_EVERY_LEVELS = 10;
    public static final int LEGACY_MAX_LEVEL = 2000;
    public static final int LEGACY_POINTS_EVERY_LEVELS = 500;
    public static final double BASE_ATTRIBUTE_CAP = 0.30D;
    public static final double POINT_ATTRIBUTE_CAP = 0.20D;
    public static final double POINT_ATTRIBUTE_BONUS = 0.05D;
    public static final double MAX_TITLE_XP_BONUS = 0.50D;
    private static final int MAX_TREES = 64;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Set<String> LEGACY_DEFAULT_DESCRIPTIONS = Set.of(
            "基础技能，达到等级后可解锁。",
            "提升该玩法的效率方向。",
            "提升该玩法的收益方向。",
            "终极技能，须投入技能点解锁。",
            "达到 Lv.1 自动解锁；该技能树的等级属性开始生效。",
            "解锁后，对应有效玩法获得的技能经验提高 10%。",
            "解锁后，对应有效玩法获得的技能经验再提高 15%。",
            "对应有效玩法触发 10 秒终极效果；冷却 60 秒。");
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
            JsonObject raw = root.getAsJsonObject();
            boolean missingTuning = raw.get("trees") != null && raw.get("trees").isJsonArray()
                    && raw.getAsJsonArray("trees").asList().stream().anyMatch(tree -> tree.isJsonObject()
                    && tree.getAsJsonObject().get("skills") != null && tree.getAsJsonObject().get("skills").isJsonArray()
                    && tree.getAsJsonObject().getAsJsonArray("skills").asList().stream()
                    .anyMatch(skill -> skill.isJsonObject() && !skill.getAsJsonObject().has("tuning")));
            SkillTreeConfig loaded = parse(raw);
            if (loaded.settings().maxLevel() == LEGACY_MAX_LEVEL) {
                // Preserve legacy player SavedData, but move the active configuration to the
                // modern professional tree model. Administrators can re-apply custom values
                // after reviewing the generated file.
                SkillTreeConfig migrated = defaults();
                save(migrated);
                return migrated;
            }
            SkillTreeConfig migrated = migrateDefaultDescriptions(loaded);
            if (migrated != loaded || missingTuning) {
                save(migrated);
            }
            return migrated;
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
                modernTree("miner", "矿工", "minecraft:diamond_pickaxe", SkillAttribute.BLOCK_BREAK_SPEED, SkillXpSource.BLOCK_BREAK, "矿脉爆发", "丰收矿工"),
                modernTree("lumberjack", "伐木工", "minecraft:diamond_axe", SkillAttribute.BLOCK_BREAK_SPEED, SkillXpSource.BLOCK_BREAK, "伐木专注", "林木馈赠"),
                modernTree("farmer", "农夫", "minecraft:diamond_hoe", SkillAttribute.LUCK, SkillXpSource.SURVIVAL, "丰收时刻", "自然馈赠"),
                modernTree("hunter", "狩猎者", "minecraft:bow", SkillAttribute.LUCK, SkillXpSource.ENTITY_KILL, "猎手标记", "战利品直觉"),
                modernTree("warrior", "战士", "minecraft:iron_sword", SkillAttribute.ATTACK_DAMAGE, SkillXpSource.ENTITY_KILL, "破阵冲锋", "致命节奏"),
                modernTree("guardian", "守卫", "minecraft:shield", SkillAttribute.ARMOR, SkillXpSource.ENTITY_KILL, "守护壁垒", "坚守反击"),
                modernTree("healing", "治疗与辅助", "minecraft:golden_apple", SkillAttribute.MAX_HEALTH, SkillXpSource.SURVIVAL, "复苏领域", "战地调律"),
                modernTree("smithing", "锻造与制造", "minecraft:anvil", SkillAttribute.LUCK, SkillXpSource.CRAFT, "匠心专注", "精工节约"),
                modernTree("alchemy", "炼金与附魔", "minecraft:brewing_stand", SkillAttribute.LUCK, SkillXpSource.CRAFT, "元素灌注", "配方熟练"),
                modernTree("exploration", "探险与探索", "minecraft:compass", SkillAttribute.MOVEMENT_SPEED, SkillXpSource.SURVIVAL, "远见侦察", "探险直觉")));
    }

    private static TreeDefinition modernTree(String id, String display, String icon, SkillAttribute attribute,
                                              SkillXpSource source, String active, String passive) {
        return new TreeDefinition(id, display, icon, item(icon), attribute,
                Set.of(source, SkillXpSource.REWARD, SkillXpSource.COMMAND),
                List.of(new LevelMultiplier(1, 1.0D)),
                List.of(new SkillDefinition("active", active, "主动技能：专业达到 100 级后解锁。", SkillKind.ACTIVE, 100, 10, 0,
                                Tuning.active(id)),
                        new SkillDefinition("passive", passive, "被动技能：专业达到 100 级后解锁。", SkillKind.PASSIVE, 100, 10, 0,
                                Tuning.passive(id))));
    }

    private static TreeDefinition tree(String id, String display, String icon, SkillAttribute attribute,
                                       SkillXpSource primarySource, SkillCopy first, SkillCopy second,
                                       SkillCopy third, SkillCopy fourth) {
        return new TreeDefinition(id, display, icon, item(icon), attribute,
                Set.of(primarySource, SkillXpSource.REWARD, SkillXpSource.COMMAND),
                List.of(new LevelMultiplier(1, 1.0D), new LevelMultiplier(501, 1.25D),
                        new LevelMultiplier(1001, 1.6D), new LevelMultiplier(1501, 2.0D)),
                List.of(new SkillDefinition("foundation", first.display(), first.description(), 1, 0),
                        new SkillDefinition("efficiency", second.display(), second.description(), 250, 1),
                        new SkillDefinition("yield", third.display(), third.description(), 750, 1),
                        new SkillDefinition("ultimate", fourth.display(), fourth.description(), 1500, 2)));
    }

    private static SkillCopy skill(String display, String description) {
        return new SkillCopy(display, description);
    }

    private record SkillCopy(String display, String description) {
    }

    /**
     * Existing configurations retain administrator-authored text. Only the exact legacy defaults
     * of the built-in trees are upgraded to their more informative descriptions.
     */
    static SkillTreeConfig migrateDefaultDescriptions(SkillTreeConfig config) {
        SkillTreeConfig defaults = defaults();
        List<TreeDefinition> updatedTrees = new ArrayList<>();
        boolean changed = false;
        for (TreeDefinition tree : config.trees()) {
            TreeDefinition defaultTree = defaults.tree(tree.id()).orElse(null);
            if (defaultTree == null) {
                // Keep the legacy description migration available even after defaults switched
                // to the modern ten-profession model.
                List<SkillDefinition> legacySkills = new ArrayList<>();
                for (SkillDefinition current : tree.skills()) {
                    String replacement = switch (current.description()) {
                        case "基础技能，达到等级后可解锁。" -> switch (tree.id()) {
                            case "combat" -> "自动解锁。战斗等级带来的常驻攻击伤害加成开始生效。";
                            case "gathering" -> "自动解锁。采集等级带来的常驻采掘速度加成开始生效。";
                            default -> current.description();
                        };
                        default -> current.description();
                    };
                    if (!replacement.equals(current.description())) {
                        legacySkills.add(new SkillDefinition(current.id(), current.display(), replacement,
                                current.kind(), current.unlockLevel(), current.maxLevel(), current.pointCost()));
                        changed = true;
                    } else legacySkills.add(current);
                }
                updatedTrees.add(changedForTree(tree.skills(), legacySkills) ? new TreeDefinition(tree.id(), tree.display(),
                        tree.iconId(), tree.icon(), tree.attribute(), tree.sources(), tree.levelMultipliers(), legacySkills) : tree);
                continue;
            }
            List<SkillDefinition> updatedSkills = new ArrayList<>();
            for (int index = 0; index < tree.skills().size(); index++) {
                SkillDefinition current = tree.skills().get(index);
                SkillDefinition replacement = defaultTree.skills().get(index);
                if (current.id().equals(replacement.id()) && LEGACY_DEFAULT_DESCRIPTIONS.contains(current.description())) {
                    updatedSkills.add(new SkillDefinition(current.id(), current.display(), replacement.description(),
                            current.unlockLevel(), current.pointCost()));
                    changed = true;
                } else {
                    updatedSkills.add(current);
                }
            }
            updatedTrees.add(changedForTree(tree.skills(), updatedSkills) ? new TreeDefinition(tree.id(), tree.display(),
                    tree.iconId(), tree.icon(), tree.attribute(), tree.sources(), tree.levelMultipliers(), updatedSkills) : tree);
        }
        return changed ? new SkillTreeConfig(config.formatVersion(), config.settings(), updatedTrees) : config;
    }

    private static boolean changedForTree(List<SkillDefinition> current, List<SkillDefinition> updated) {
        return !current.equals(updated);
    }

    public record Settings(int maxLevel, int pointsEveryLevels, long maxDailyXp, int minIntervalTicks,
                           double baseAttributeCap, double pointAttributeCap, double pointAttributeBonus,
                           double maxTitleXpBonus, long xpBase, long xpLinear, double xpQuadratic,
                           AnnouncementSettings announcements, long pointRewardCurrency) {
        public Settings {
            boolean modern = maxLevel == MAX_LEVEL && pointsEveryLevels == POINTS_EVERY_LEVELS;
            boolean legacy = maxLevel == LEGACY_MAX_LEVEL && pointsEveryLevels == LEGACY_POINTS_EVERY_LEVELS;
            if (!modern && !legacy) throw new JsonParseException("skills.settings progression must be 100/10 or legacy 2000/500");
            if (maxDailyXp < 1L || maxDailyXp > 1_000_000_000L) throw new JsonParseException("skills.settings.max_daily_xp is invalid");
            if (minIntervalTicks < 0 || minIntervalTicks > 1200) throw new JsonParseException("skills.settings.min_interval_ticks is invalid");
            if (!fraction(baseAttributeCap) || !fraction(pointAttributeCap) || !fraction(pointAttributeBonus)
                    || !fraction(maxTitleXpBonus) || !Double.isFinite(xpQuadratic) || xpQuadratic < 0.0D) {
                throw new JsonParseException("skills.settings contains an invalid percentage or XP curve value");
            }
            if (!same(baseAttributeCap, BASE_ATTRIBUTE_CAP)) throw new JsonParseException("skills.settings.base_attribute_cap is fixed at 0.30");
            if (!same(pointAttributeCap, POINT_ATTRIBUTE_CAP)) throw new JsonParseException("skills.settings.point_attribute_cap is fixed at 0.20");
            if (!same(pointAttributeBonus, POINT_ATTRIBUTE_BONUS)) throw new JsonParseException("skills.settings.point_attribute_bonus is fixed at 0.05");
            if (!same(maxTitleXpBonus, MAX_TITLE_XP_BONUS)) throw new JsonParseException("skills.settings.max_title_xp_bonus is fixed at 0.50");
            if (xpBase < 1L || xpLinear < 0L) throw new JsonParseException("skills XP curve values are invalid");
            announcements = announcements == null ? AnnouncementSettings.defaults() : announcements;
            if (pointRewardCurrency < 0L || pointRewardCurrency > 1_000_000_000L) {
                throw new JsonParseException("skills.settings.point_reward_currency is invalid");
            }
        }

        /** Compatibility constructor for callers that only configure the original progression fields. */
        public Settings(int maxLevel, int pointsEveryLevels, long maxDailyXp, int minIntervalTicks,
                        double baseAttributeCap, double pointAttributeCap, double pointAttributeBonus,
                        double maxTitleXpBonus, long xpBase, long xpLinear, double xpQuadratic) {
            this(maxLevel, pointsEveryLevels, maxDailyXp, minIntervalTicks, baseAttributeCap, pointAttributeCap,
                    pointAttributeBonus, maxTitleXpBonus, xpBase, xpLinear, xpQuadratic,
                    AnnouncementSettings.defaults(), 250L);
        }

        static Settings defaults() {
            return new Settings(MAX_LEVEL, POINTS_EVERY_LEVELS, 250_000L, 4, BASE_ATTRIBUTE_CAP,
                    POINT_ATTRIBUTE_CAP, POINT_ATTRIBUTE_BONUS, MAX_TITLE_XP_BONUS, 100L, 25L, 0.015D,
                    AnnouncementSettings.defaults(), 250L);
        }

        static Settings parse(JsonObject object) {
            return new Settings(integer(object, "max_level", MAX_LEVEL, "skills.settings"),
                    integer(object, "points_every_levels", POINTS_EVERY_LEVELS, "skills.settings"),
                    positiveLong(object, "max_daily_xp", 250_000L, "skills.settings"),
                    integer(object, "min_interval_ticks", 4, "skills.settings"),
                    decimal(object, "base_attribute_cap", 0.30D, "skills.settings"),
                    decimal(object, "point_attribute_cap", 0.20D, "skills.settings"),
                    decimal(object, "point_attribute_bonus", 0.05D, "skills.settings"),
                    decimal(object, "max_title_xp_bonus", 0.50D, "skills.settings"),
                    positiveLong(object, "xp_base", 100L, "skills.settings"),
                    positiveLong(object, "xp_linear", 25L, "skills.settings"),
                    decimal(object, "xp_quadratic", 0.015D, "skills.settings"),
                    AnnouncementSettings.parse(optionalObject(object, "announcements", "skills.settings")),
                    nonNegativeLong(object, "point_reward_currency", 250L, "skills.settings"));
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
            object.add("announcements", announcements.toJson());
            object.addProperty("point_reward_currency", pointRewardCurrency);
            return object;
        }
    }

    /** Chat delivery settings for server-wide skill milestones. */
    public record AnnouncementSettings(boolean enabled, int minimumLevel, int cooldownSeconds,
                                       String channel, String color) {
        private static final Set<String> COLORS = Set.of("black", "dark_blue", "dark_green", "dark_aqua",
                "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua",
                "red", "light_purple", "yellow", "white");

        public AnnouncementSettings {
            if (minimumLevel < 100 || minimumLevel > MAX_LEVEL) {
                throw new JsonParseException("skills.settings.announcements.minimum_level must be 100-2000");
            }
            if (cooldownSeconds < 0 || cooldownSeconds > 3600) {
                throw new JsonParseException("skills.settings.announcements.cooldown_seconds must be 0-3600");
            }
            channel = channel == null ? "chat" : channel.trim().toLowerCase(Locale.ROOT);
            if (!channel.equals("chat") && !channel.equals("action_bar")) {
                throw new JsonParseException("skills.settings.announcements.channel must be chat or action_bar");
            }
            color = color == null ? "gold" : color.trim().toLowerCase(Locale.ROOT);
            if (!COLORS.contains(color)) {
                throw new JsonParseException("skills.settings.announcements.color must be a Minecraft chat color");
            }
        }

        static AnnouncementSettings defaults() { return new AnnouncementSettings(true, 100, 60, "chat", "gold"); }

        static AnnouncementSettings parse(JsonObject object) {
            if (object == null) return defaults();
            ConfigFieldReporter.warnUnknown(object, "skills.settings.announcements",
                    Set.of("enabled", "minimum_level", "cooldown_seconds", "channel", "color"));
            return new AnnouncementSettings(bool(object, "enabled", true, "skills.settings.announcements"),
                    integer(object, "minimum_level", 100, "skills.settings.announcements"),
                    integer(object, "cooldown_seconds", 60, "skills.settings.announcements"),
                    string(object, "channel", "chat"), string(object, "color", "gold"));
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("enabled", enabled);
            object.addProperty("minimum_level", minimumLevel);
            object.addProperty("cooldown_seconds", cooldownSeconds);
            object.addProperty("channel", channel);
            object.addProperty("color", color);
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
            if (skills.size() != REQUIRED_SKILL_COUNT && skills.size() != 4) {
                throw new JsonParseException("skill tree " + id + " must define exactly 2 modern or 4 legacy skills");
            }
            Set<String> skillIds = new LinkedHashSet<>();
            int[] unlockLevels = {1, 250, 750, 1500};
            int[] pointCosts = {0, 1, 1, 2};
            for (int index = 0; index < skills.size(); index++) {
                SkillDefinition skill = skills.get(index);
                if (!skillIds.add(skill.id())) throw new JsonParseException("skill tree " + id + " has duplicate skill ids");
                if (skills.size() == 4 && (skill.unlockLevel() != unlockLevels[index] || skill.pointCost() != pointCosts[index])) {
                    throw new JsonParseException("skill tree " + id + " must use fixed four-skill unlock stages 1/250/750/1500");
                }
                if (skills.size() == 2 && (skill.unlockLevel() != MAX_LEVEL || skill.maxLevel() != 10
                        || skill.pointCost() != 0 || skill.kind() == null)) {
                    throw new JsonParseException("modern skill tree " + id + " requires active/passive level-10 skills unlocked at level 100");
                }
            }
            if (skills.size() == 2 && skills.get(0).kind() == skills.get(1).kind()) {
                throw new JsonParseException("modern skill tree " + id + " must contain one active and one passive skill");
            }
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
                skills.add(SkillDefinition.parse(entry.getAsJsonObject(), context + ".skills", settings, id));
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

    public record SkillDefinition(String id, String display, String description, SkillKind kind,
                                  int unlockLevel, int maxLevel, int pointCost, Tuning tuning) {
        /** Legacy four-node compatibility constructor. */
        public SkillDefinition(String id, String display, String description, int unlockLevel, int pointCost) {
            this(id, display, description, SkillKind.PASSIVE, unlockLevel, 1, pointCost, Tuning.legacy());
        }
        public SkillDefinition(String id, String display, String description, SkillKind kind,
                               int unlockLevel, int maxLevel, int pointCost) {
            this(id, display, description, kind, unlockLevel, maxLevel, pointCost,
                    kind == SkillKind.ACTIVE ? Tuning.active("") : Tuning.passive(""));
        }
        public SkillDefinition {
            id = normalizedId(id, "skill id");
            if (display == null || display.isBlank() || display.length() > 64 || description == null || description.length() > 256) throw new JsonParseException("skill definition text is invalid");
            if (unlockLevel < 1 || maxLevel < 1 || maxLevel > 10 || pointCost < 0 || pointCost > 4) throw new JsonParseException("skill unlock requirements are invalid");
            tuning = tuning == null ? (kind == SkillKind.ACTIVE ? Tuning.active("") : Tuning.passive("")) : tuning;
        }
        static SkillDefinition parse(JsonObject object, String context, Settings settings, String treeId) {
            ConfigFieldReporter.warnUnknown(object, context, Set.of("id", "display", "description", "kind", "unlock_level", "max_level", "point_cost", "tuning"));
            SkillKind kind = parseKind(object, "kind", SkillKind.PASSIVE, context);
            SkillDefinition definition = new SkillDefinition(requiredString(object, "id", context), requiredString(object, "display", context),
                    string(object, "description", ""), kind, integer(object, "unlock_level", -1, context),
                    integer(object, "max_level", 1, context), integer(object, "point_cost", 0, context),
                    Tuning.parse(optionalObject(object, "tuning", context), context + ".tuning", kind, treeId));
            if (definition.unlockLevel() > settings.maxLevel()) throw new JsonParseException(context + ".unlock_level exceeds max_level");
            return definition;
        }
        JsonObject toJson() { JsonObject object = new JsonObject(); object.addProperty("id", id); object.addProperty("display", display); object.addProperty("description", description); object.addProperty("kind", kind.serializedName()); object.addProperty("unlock_level", unlockLevel); object.addProperty("max_level", maxLevel); object.addProperty("point_cost", pointCost); object.add("tuning", tuning.toJson()); return object; }
    }

    /** Data-driven level-1 to level-10 values; callers interpolate linearly and clamp additively. */
    public record Tuning(int minDurationSeconds, int maxDurationSeconds, int maxCooldownSeconds, int minCooldownSeconds,
                         double minValue, double maxValue) {
        public Tuning {
            if (minDurationSeconds < 0 || maxDurationSeconds < minDurationSeconds || maxDurationSeconds > 600
                    || minCooldownSeconds < 0 || maxCooldownSeconds < minCooldownSeconds || maxCooldownSeconds > 86_400
                    || !Double.isFinite(minValue) || !Double.isFinite(maxValue) || minValue < 0D || maxValue < minValue || maxValue > 1D) {
                throw new JsonParseException("skill tuning values are invalid");
            }
        }
        static Tuning active(String treeId) {
            int minDuration = switch (treeId) { case "lumberjack" -> 20; case "hunter" -> 20; case "guardian" -> 10; default -> 30; };
            int maxDuration = switch (treeId) { case "lumberjack" -> 90; case "hunter" -> 60; case "guardian" -> 25; default -> 120; };
            return new Tuning(minDuration, maxDuration, 1800, 600, 0D, 0D);
        }
        static Tuning passive(String treeId) {
            return switch (treeId) {
                case "farmer" -> new Tuning(0, 0, 0, 0, 0.04D, 0.30D);
                case "lumberjack" -> new Tuning(0, 0, 0, 0, 0.05D, 0.35D);
                case "hunter", "alchemy" -> new Tuning(0, 0, 0, 0, 0.05D, 0.25D);
                case "guardian", "smithing" -> new Tuning(0, 0, 0, 0, 0.05D, 0.30D);
                default -> new Tuning(0, 0, 0, 0, 0.05D, 0.40D);
            };
        }
        static Tuning legacy() { return new Tuning(0, 0, 0, 0, 0D, 0D); }
        static Tuning parse(JsonObject object, String context, SkillKind kind, String treeId) {
            if (object == null) return kind == SkillKind.ACTIVE ? active(treeId) : passive(treeId);
            ConfigFieldReporter.warnUnknown(object, context, Set.of("min_duration_seconds", "max_duration_seconds", "max_cooldown_seconds", "min_cooldown_seconds", "min_value", "max_value"));
            return new Tuning(integer(object, "min_duration_seconds", 0, context), integer(object, "max_duration_seconds", 0, context),
                    integer(object, "max_cooldown_seconds", 0, context), integer(object, "min_cooldown_seconds", 0, context),
                    decimal(object, "min_value", 0D, context), decimal(object, "max_value", 0D, context));
        }
        JsonObject toJson() { JsonObject o = new JsonObject(); o.addProperty("min_duration_seconds", minDurationSeconds); o.addProperty("max_duration_seconds", maxDurationSeconds); o.addProperty("max_cooldown_seconds", maxCooldownSeconds); o.addProperty("min_cooldown_seconds", minCooldownSeconds); o.addProperty("min_value", minValue); o.addProperty("max_value", maxValue); return o; }
    }

    public enum SkillKind {
        ACTIVE, PASSIVE;
        static SkillKind parse(String value) {
            try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (RuntimeException exception) { throw new JsonParseException("skill kind must be active or passive"); }
        }
        String serializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    private static Item item(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) throw new JsonParseException("invalid item identifier: " + id);
        return BuiltInRegistries.ITEM.getOptional(identifier).orElseThrow(() -> new JsonParseException("unknown item: " + id));
    }
    private static boolean fraction(double value) { return Double.isFinite(value) && value >= 0.0D && value <= 1.0D; }
    private static boolean same(double left, double right) { return Math.abs(left - right) < 0.000_001D; }
    private static JsonObject object(JsonObject root, String key, String context) { JsonElement value = root.get(key); if (value == null || !value.isJsonObject()) throw new JsonParseException(context + "." + key + " must be an object"); return value.getAsJsonObject(); }
    private static JsonObject optionalObject(JsonObject root, String key, String context) { JsonElement value = root.get(key); if (value == null) return null; if (!value.isJsonObject()) throw new JsonParseException(context + "." + key + " must be an object"); return value.getAsJsonObject(); }
    private static JsonArray array(JsonObject root, String key, String context) { JsonElement value = root.get(key); if (value == null || !value.isJsonArray()) throw new JsonParseException(context + "." + key + " must be an array"); return value.getAsJsonArray(); }
    private static String requiredString(JsonObject object, String key, String context) { String value = string(object, key, ""); if (value.isBlank()) throw new JsonParseException(context + "." + key + " must be a non-empty string"); return value.trim(); }
    private static String string(JsonObject object, String key, String fallback) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new JsonParseException(key + " must be a string"); return value.getAsString(); }
    private static int integer(JsonObject object, String key, int fallback, String context) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException(context + "." + key + " must be an integer"); try { return Integer.parseInt(value.getAsString()); } catch (NumberFormatException exception) { throw new JsonParseException(context + "." + key + " must be an integer"); } }
    private static long positiveLong(JsonObject object, String key, long fallback, String context) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException(context + "." + key + " must be a positive integer"); try { long result = Long.parseLong(value.getAsString()); if (result < 1L) throw new NumberFormatException(); return result; } catch (NumberFormatException exception) { throw new JsonParseException(context + "." + key + " must be a positive integer"); } }
    private static long nonNegativeLong(JsonObject object, String key, long fallback, String context) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException(context + "." + key + " must be a non-negative integer"); try { long result = Long.parseLong(value.getAsString()); if (result < 0L) throw new NumberFormatException(); return result; } catch (NumberFormatException exception) { throw new JsonParseException(context + "." + key + " must be a non-negative integer"); } }
    private static double decimal(JsonObject object, String key, double fallback, String context) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException(context + "." + key + " must be a number"); double result = value.getAsDouble(); if (!Double.isFinite(result)) throw new JsonParseException(context + "." + key + " must be finite"); return result; }
    private static boolean bool(JsonObject object, String key, boolean fallback, String context) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new JsonParseException(context + "." + key + " must be a boolean"); return value.getAsBoolean(); }
    private static SkillKind parseKind(JsonObject object, String key, SkillKind fallback, String context) { JsonElement value = object.get(key); if (value == null) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new JsonParseException(context + "." + key + " must be a string"); return SkillKind.parse(value.getAsString()); }
    private static String normalizedId(String value, String context) { String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT); if (!ID_PATTERN.matcher(id).matches()) throw new JsonParseException(context + " must match " + ID_PATTERN.pattern()); return id; }
}
