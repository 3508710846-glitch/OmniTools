package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.achievement.AchievementCondition;
import dev.modmind.omnitools.achievement.AllCondition;
import dev.modmind.omnitools.achievement.AnyCondition;
import dev.modmind.omnitools.achievement.NotCondition;
import dev.modmind.omnitools.achievement.StatCondition;
import dev.modmind.omnitools.achievement.StatisticEvaluationContext;
import dev.modmind.omnitools.achievement.StatisticTargetResolver;
import dev.modmind.omnitools.statistics.StatisticQuery;
import dev.modmind.omnitools.achievement.StatisticUnit;
import dev.modmind.omnitools.achievement.SumCondition;
import dev.modmind.omnitools.achievement.TargetMatch;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ConfigFieldReporter;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.config.CommonConfig;
import dev.modmind.omnitools.reward.RewardDefinition;
import dev.modmind.omnitools.reward.RewardType;
import dev.modmind.omnitools.entitlement.TimedEntitlement;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
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
import java.util.Set;
import java.util.regex.Pattern;

/** Server-side achievement definitions backed by a small, versioned condition model. */
public final class AchievementConfig {
    public static final String FILE_NAME = "omnitools-achievements.json";
    public static final int CURRENT_FORMAT_VERSION = 2;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final int MAX_DISPLAY_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 512;
    private static final int MAX_CONDITION_LEAVES = 128;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.ACHIEVEMENTS);

    private final List<AchievementDefinition> achievements;
    private final Map<String, AchievementDefinition> byId;
    private final SchedulerConfig scheduler;

    private AchievementConfig(List<AchievementDefinition> achievements) {
        this(achievements, SchedulerConfig.defaults());
    }

    private AchievementConfig(List<AchievementDefinition> achievements, SchedulerConfig scheduler) {
        this.achievements = List.copyOf(achievements);
        Map<String, AchievementDefinition> indexed = new LinkedHashMap<>();
        for (AchievementDefinition achievement : achievements) {
            indexed.put(achievement.id(), achievement);
        }
        this.byId = Collections.unmodifiableMap(indexed);
        this.scheduler = scheduler == null ? SchedulerConfig.defaults() : scheduler;
    }

    public static AchievementConfig load(HolderLookup.Provider registries) {
        return load(registries, CommonConfig.empty());
    }

    public static AchievementConfig load(HolderLookup.Provider registries, CommonConfig common) {
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
            return parse(root.getAsJsonObject(), registries, common);
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            System.err.println("[omnitools] Could not load " + FILE + ": " + exception.getMessage()
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

    public SchedulerConfig scheduler() {
        return scheduler;
    }

    private static AchievementConfig parse(JsonObject root, HolderLookup.Provider registries, CommonConfig common) {
        ConfigFieldReporter.warnUnknown(root, "achievements", Set.of("format_version", "target_groups",
                "check_scheduler", "achievements"));
        int version = integer(root, "format_version", 1);
        if (version < 1 || version > CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("Unsupported achievement format_version: " + version);
        }
        Map<String, List<String>> targetGroups = parseTargetGroups(root.get("target_groups"));
        SchedulerConfig scheduler = parseScheduler(root.get("check_scheduler"));
        validateTargetGroupGraph(targetGroups);
        JsonElement element = root.get("achievements");
        if (element == null || !element.isJsonArray()) {
            throw new JsonParseException("achievements must be an array");
        }
        JsonArray array = element.getAsJsonArray();
        List<AchievementDefinition> definitions = new ArrayList<>();
        Map<String, Boolean> ids = new LinkedHashMap<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement entry = array.get(index);
            if (!entry.isJsonObject()) {
                throw new JsonParseException("Achievement entry " + index + " must be an object");
            }
            JsonObject achievement = entry.getAsJsonObject();
            ConfigFieldReporter.warnUnknown(achievement, "achievements[" + index + "]",
                    Set.of("id", "display", "description", "icon", "requirements", "rewards"));
            String id = normalizeId(requiredString(achievement, "id"));
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new JsonParseException("Achievement id " + id + " must match " + ID_PATTERN.pattern());
            }
            if (ids.put(id, Boolean.TRUE) != null) {
                throw new JsonParseException("Achievement id " + id + " is configured more than once");
            }
            String display = requiredString(achievement, "display");
            if (display.length() > MAX_DISPLAY_LENGTH || LegacyTitleText.plainText(display).isBlank()) {
                throw new JsonParseException("Achievement display for " + id + " is invalid");
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

            JsonElement requirements = common == null ? achievement.get("requirements")
                    : common.expandCondition(achievement.get("requirements"), "requirements for " + id);
            ParseState state = new ParseState(targetGroups);
            ParsedCondition parsed;
            if (requirements != null && requirements.isJsonArray()
                    && isV2ConditionArray(requirements, id)) {
                // Accept the common migration form where v2 condition nodes are
                // kept in an array. The array has the same semantics as an all node.
                parsed = parseV2ConditionArray(id, requirements, state);
            } else if (version == 1 || (requirements != null && requirements.isJsonArray())) {
                parsed = parseLegacyRequirements(id, requirements, state);
            } else {
                parsed = parseV2Condition(id, requirements, state);
            }
            if (!parsed.hasPositiveStat()) {
                throw new JsonParseException("Achievement " + id
                        + " must contain at least one positive statistic condition");
            }
            List<RewardDefinition> rewards = parseRewards(achievement.get("rewards"), id, registries, common);
            definitions.add(new AchievementDefinition(id, display, description, iconId, icon,
                    parsed.requirements(), parsed.condition(), rewards));
        }
        return new AchievementConfig(definitions, scheduler);
    }

    private static SchedulerConfig parseScheduler(JsonElement element) {
        if (element == null) {
            return SchedulerConfig.defaults();
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException("check_scheduler must be an object");
        }
        JsonObject scheduler = element.getAsJsonObject();
        ConfigFieldReporter.warnUnknown(scheduler, "achievements.check_scheduler",
                Set.of("check_interval_ticks", "max_players_per_tick", "max_conditions_per_tick",
                        "full_recheck_seconds"));
        return new SchedulerConfig(
                rangedInteger(scheduler, "check_interval_ticks", SchedulerConfig.defaults().checkIntervalTicks(), 1, 1_200),
                rangedInteger(scheduler, "max_players_per_tick", SchedulerConfig.defaults().maxPlayersPerTick(), 1, 1_000),
                rangedInteger(scheduler, "max_conditions_per_tick", SchedulerConfig.defaults().maxConditionsPerTick(), 1, 16_384),
                rangedInteger(scheduler, "full_recheck_seconds", SchedulerConfig.defaults().fullRecheckSeconds(), 1, 86_400));
    }

    private static boolean isV2ConditionArray(JsonElement element, String achievementId) {
        if (!element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            return false;
        }
        boolean hasV2Node = false;
        boolean hasLegacyNode = false;
        for (JsonElement child : element.getAsJsonArray()) {
            if (!child.isJsonObject()) {
                return false;
            }
            JsonElement typeElement = child.getAsJsonObject().get("type");
            if (typeElement == null && (child.getAsJsonObject().has("template")
                    || child.getAsJsonObject().has("$ref"))) {
                hasV2Node = true;
                continue;
            }
            if (typeElement == null || !typeElement.isJsonPrimitive()
                    || !typeElement.getAsJsonPrimitive().isString()) {
                return false;
            }
            String type = typeElement.getAsString().trim().toLowerCase(Locale.ROOT);
            if (isV2ConditionType(type)) {
                hasV2Node = true;
            } else {
                hasLegacyNode = true;
            }
        }
        if (hasV2Node && hasLegacyNode) {
            throw new JsonParseException("Achievement " + achievementId
                    + " cannot mix v1 requirements and v2 condition nodes in one array");
        }
        return hasV2Node;
    }

    private static boolean isV2ConditionType(String type) {
        return switch (type) {
            case "stat", "sum", "all", "any", "not" -> true;
            default -> false;
        };
    }

    private static ParsedCondition parseV2ConditionArray(String achievementId, JsonElement element,
                                                         ParseState state) {
        JsonArray array = element.getAsJsonArray();
        List<AchievementCondition> children = new ArrayList<>();
        List<Requirement> requirements = new ArrayList<>();
        boolean hasPositiveStat = false;
        for (int index = 0; index < array.size(); index++) {
            JsonElement childElement = array.get(index);
            if (!childElement.isJsonObject()) {
                throw new JsonParseException("Condition " + achievementId + "[" + index
                        + "] must be an object");
            }
            ParsedNode child = parseCondition(childElement.getAsJsonObject(), achievementId, 1, state);
            children.add(child.condition());
            requirements.addAll(child.requirements());
            hasPositiveStat |= child.hasPositiveStat();
        }
        return new ParsedCondition(requirements, new AllCondition(children), hasPositiveStat);
    }

    private static ParsedCondition parseLegacyRequirements(String achievementId, JsonElement element, ParseState state) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new JsonParseException("Achievement " + achievementId + " must contain requirements array");
        }
        List<Requirement> requirements = new ArrayList<>();
        List<AchievementCondition> children = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            JsonElement value = array.get(index);
            if (!value.isJsonObject()) {
                throw new JsonParseException("Requirement " + achievementId + "[" + index + "] must be an object");
            }
            JsonObject requirement = value.getAsJsonObject();
            RequirementType type = RequirementType.parse(requiredString(requirement, "type"));
            String targetId = requiredString(requirement, "target");
            long count = positiveLong(requirement, "count");
            Requirement parsed = requirement(type, targetId, count,
                    "requirement target for achievement " + achievementId);
            state.addLeaf(achievementId);
            requirements.add(parsed);
            children.add(new StatCondition(List.of(parsed), count, TargetMatch.SUM,
                    unitFor(type, targetId)));
        }
        return new ParsedCondition(requirements, new AllCondition(children), true);
    }

    private static ParsedCondition parseV2Condition(String achievementId, JsonElement element, ParseState state) {
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException("Achievement " + achievementId + " must contain a condition object");
        }
        ParsedNode parsed = parseCondition(element.getAsJsonObject(), achievementId, 0, state);
        return new ParsedCondition(parsed.requirements(), parsed.condition(), parsed.hasPositiveStat());
    }

    private static ParsedNode parseCondition(JsonObject condition, String achievementId, int depth, ParseState state) {
        if (depth >= 8) {
            throw new JsonParseException("Achievement " + achievementId + " condition nesting exceeds 8 levels");
        }
        String type = requiredString(condition, "type").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "stat" -> parseStatCondition(achievementId, condition, state);
            case "sum" -> parseSumCondition(achievementId, condition, state);
            case "all", "any" -> parseChildrenCondition(achievementId, condition, depth, type, state);
            case "not" -> parseNotCondition(achievementId, condition, depth, state);
            default -> throw new JsonParseException("Unknown achievement condition type: " + type);
        };
    }

    private static ParsedNode parseStatCondition(String achievementId, JsonObject condition, ParseState state) {
        state.addLeaf(achievementId);
        String stat = requiredString(condition, "stat");
        RequirementType requirementType;
        try {
            requirementType = RequirementType.parse(stat);
        } catch (JsonParseException exception) {
            throw new JsonParseException("Unsupported achievement stat: " + stat
                    + ". Supported values are block_mined, item_crafted, item_used, item_broken, "
                    + "item_picked_up, item_dropped, entity_killed, entity_killed_by and custom");
        }
        TargetMatch match;
        try {
            match = TargetMatch.parse(optionalString(condition, "match", "sum"));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(exception.getMessage());
        }
        long atLeast = positiveLong(condition, "at_least");
        List<Requirement> requirements = new ArrayList<>();
        if (requirementType == RequirementType.CUSTOM) {
            if (condition.has("targets")) {
                throw new JsonParseException("custom stat condition " + achievementId
                        + " must use custom_stat instead of targets");
            }
            String customStat = requiredString(condition, "custom_stat");
            String unit = optionalString(condition, "unit", "count");
            long progressDivisor = progressDisplayDivisor(customStat, unit);
            atLeast = convertCustomThreshold(customStat, atLeast, unit);
            requirements.add(requirement(requirementType, customStat, atLeast,
                    "custom stat target for achievement " + achievementId));
            return new ParsedNode(requirements, new StatCondition(requirements, atLeast, match,
                    unitFor(requirementType, customStat), progressDivisor,
                    progressDisplayUnit(customStat, unit)), true);
        }
        JsonElement targetsElement = condition.get("targets");
        if (targetsElement == null || !targetsElement.isJsonArray() || targetsElement.getAsJsonArray().isEmpty()) {
            throw new JsonParseException("stat condition " + achievementId + " must contain targets");
        }
        List<String> rawTargets = new ArrayList<>();
        for (JsonElement targetElement : targetsElement.getAsJsonArray()) {
            if (!targetElement.isJsonPrimitive() || !targetElement.getAsJsonPrimitive().isString()) {
                throw new JsonParseException("stat targets for " + achievementId + " must contain strings");
            }
            rawTargets.add(targetElement.getAsString().trim());
        }
        List<String> targets;
        try {
            targets = StatisticQuery.resolveTargets(requirementType, rawTargets, state.targetGroups,
                    "achievement " + achievementId);
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(exception.getMessage());
        }
        for (String targetId : targets) {
            requirements.add(requirement(requirementType, targetId, atLeast,
                    "stat target for achievement " + achievementId));
        }
        return new ParsedNode(requirements, new StatCondition(requirements, atLeast, match,
                unitFor(requirementType, targets.get(0))), true);
    }

    private static ParsedNode parseSumCondition(String achievementId, JsonObject condition, ParseState state) {
        state.addLeaf(achievementId);
        long atLeast = positiveLong(condition, "at_least");
        JsonElement sourcesElement = condition.get("sources");
        if (sourcesElement == null || !sourcesElement.isJsonArray() || sourcesElement.getAsJsonArray().isEmpty()) {
            throw new JsonParseException("sum condition for " + achievementId + " must contain sources");
        }
        List<Requirement> requirements = new ArrayList<>();
        StatisticUnit unit = null;
        String firstCustomStat = null;
        long progressDivisor = 1L;
        String progressUnit = "";
        for (JsonElement sourceElement : sourcesElement.getAsJsonArray()) {
            if (!sourceElement.isJsonObject()) {
                throw new JsonParseException("sum source for " + achievementId + " must be an object");
            }
            JsonObject source = sourceElement.getAsJsonObject();
            String stat = requiredString(source, "stat");
            RequirementType type;
            try {
                type = RequirementType.parse(stat);
            } catch (JsonParseException exception) {
                throw new JsonParseException("Unsupported sum source stat: " + stat);
            }
            if (type == RequirementType.CUSTOM) {
                if (source.has("targets")) {
                    throw new JsonParseException("custom sum source must use custom_stat");
                }
                String customStat = requiredString(source, "custom_stat");
                String sourceUnit = optionalString(source, "unit", "count");
                customUnitMultiplier(customStat, sourceUnit); // validate the declared source unit
                Requirement requirement = requirement(type, customStat, 1L,
                        "custom sum source for achievement " + achievementId);
                requirements.add(requirement);
                if (requirements.size() > StatisticTargetResolver.MAX_TARGETS) {
                    throw new JsonParseException("sum condition for " + achievementId + " exceeds "
                            + StatisticTargetResolver.MAX_TARGETS + " targets");
                }
                StatisticUnit sourceCategory = unitFor(type, customStat);
                if (unit != null && unit != sourceCategory) {
                    throw new JsonParseException("sum condition for " + achievementId
                            + " cannot combine different statistic units");
                }
                unit = sourceCategory;
                if (firstCustomStat == null) {
                    firstCustomStat = customStat;
                }
                continue;
            }
            JsonElement targetsElement = source.get("targets");
            if (targetsElement == null || !targetsElement.isJsonArray()
                    || targetsElement.getAsJsonArray().isEmpty()) {
                throw new JsonParseException("sum source for " + achievementId + " must contain targets");
            }
            List<String> rawTargets = new ArrayList<>();
            for (JsonElement target : targetsElement.getAsJsonArray()) {
                if (!target.isJsonPrimitive() || !target.getAsJsonPrimitive().isString()) {
                    throw new JsonParseException("sum source targets must contain strings");
                }
                rawTargets.add(target.getAsString().trim());
            }
            List<String> targets;
            try {
                targets = StatisticQuery.resolveTargets(type, rawTargets, state.targetGroups,
                        "sum source for achievement " + achievementId);
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException(exception.getMessage());
            }
            for (String target : targets) {
                requirements.add(requirement(type, target, 1L,
                        "sum source target for achievement " + achievementId));
                if (requirements.size() > StatisticTargetResolver.MAX_TARGETS) {
                    throw new JsonParseException("sum condition for " + achievementId + " exceeds "
                            + StatisticTargetResolver.MAX_TARGETS + " targets");
                }
            }
            StatisticUnit sourceCategory = unitFor(type, targets.get(0));
            if (unit != null && unit != sourceCategory) {
                throw new JsonParseException("sum condition for " + achievementId
                        + " cannot combine different statistic units");
            }
            unit = sourceCategory;
        }
        if (condition.has("unit")) {
            String configuredUnit = optionalString(condition, "unit", "count");
            if (firstCustomStat == null) {
                if (!configuredUnit.equalsIgnoreCase("count")) {
                    throw new JsonParseException("sum condition unit " + configuredUnit
                            + " is only valid for custom statistics");
                }
            } else {
                progressDivisor = progressDisplayDivisor(firstCustomStat, configuredUnit);
                progressUnit = progressDisplayUnit(firstCustomStat, configuredUnit);
                atLeast = convertCustomThreshold(firstCustomStat, atLeast, configuredUnit);
            }
        } else if (firstCustomStat != null && unit != StatisticUnit.COUNT) {
            throw new JsonParseException("sum condition for " + achievementId
                    + " must specify a unit for distance, time or damage sources");
        }
        return new ParsedNode(requirements, new SumCondition(requirements, atLeast, unit,
                progressDivisor, progressUnit), true);
    }

    private static ParsedNode parseChildrenCondition(String achievementId, JsonObject condition,
                                                     int depth, String type, ParseState state) {
        JsonElement childrenElement = condition.get("children");
        if (childrenElement == null || !childrenElement.isJsonArray()
                || childrenElement.getAsJsonArray().isEmpty()) {
            throw new JsonParseException(type + " condition for " + achievementId
                    + " must contain at least one child");
        }
        List<AchievementCondition> children = new ArrayList<>();
        List<Requirement> requirements = new ArrayList<>();
        boolean hasPositiveStat = false;
        for (JsonElement childElement : childrenElement.getAsJsonArray()) {
            if (!childElement.isJsonObject()) {
                throw new JsonParseException(type + " child for " + achievementId + " must be an object");
            }
            ParsedNode child = parseCondition(childElement.getAsJsonObject(), achievementId, depth + 1, state);
            children.add(child.condition());
            requirements.addAll(child.requirements());
            hasPositiveStat |= child.hasPositiveStat();
        }
        AchievementCondition logical = type.equals("all")
                ? new AllCondition(children) : new AnyCondition(children);
        return new ParsedNode(requirements, logical, hasPositiveStat);
    }

    private static ParsedNode parseNotCondition(String achievementId, JsonObject condition, int depth, ParseState state) {
        JsonElement childElement = condition.get("child");
        if (childElement == null || !childElement.isJsonObject()) {
            throw new JsonParseException("not condition for " + achievementId + " must contain one child object");
        }
        ParsedNode child = parseCondition(childElement.getAsJsonObject(), achievementId, depth + 1, state);
        // A not-only tree cannot represent a bounded cumulative achievement; require a
        // positive statistic sibling somewhere in the top-level tree.
        return new ParsedNode(child.requirements(), new NotCondition(child.condition()), false);
    }

    private static Requirement requirement(RequirementType type, String targetId, long count, String context) {
        Object target = switch (type.domain()) {
            case BLOCK -> resolveBlock(targetId, context);
            case ITEM -> resolveItem(targetId, context);
            case ENTITY -> resolveEntityType(targetId, context);
            case CUSTOM -> resolveCustomStat(targetId, context);
        };
        return new Requirement(type, targetId, count, target);
    }

    private static List<RewardDefinition> parseRewards(JsonElement element, String achievementId,
                                                        HolderLookup.Provider registries, CommonConfig common) {
        if (element == null) {
            return List.of();
        }
        if (element.isJsonArray()) {
            return RewardDefinition.parseArray(element, "rewards for achievement " + achievementId, registries, common);
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException("rewards for achievement " + achievementId + " must be an array or legacy object");
        }
        // Legacy coins/titles object is converted to stable synthetic ids for existing servers.
        JsonObject reward = element.getAsJsonObject();
        long coins = nonNegativeLong(reward, "coins", 0L);
        List<RewardDefinition> rewards = new ArrayList<>();
        if (coins > 0L) {
            rewards.add(RewardDefinition.currency("legacy_" + achievementId + "_currency", coins));
        }
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
                    throw new JsonParseException("Invalid title id " + titleId + " in achievement " + achievementId);
                }
                String rewardId = "legacy_" + achievementId + "_title_" + titleId;
                if (rewards.stream().noneMatch(existing -> existing.id().equals(rewardId))) {
                    rewards.add(RewardDefinition.title(rewardId, titleId));
                }
            }
        }
        return List.copyOf(rewards);
    }

    private static AchievementConfig defaults() {
        Item icon = resolveItem("minecraft:stone", "default achievement icon");
        Requirement requirement = requirement(RequirementType.BLOCK_MINED, "minecraft:stone", 1000L,
                "default achievement target");
        AchievementDefinition definition = new AchievementDefinition(
                "stone_breaker", "石匠", "挖掘石头 1000 个", "minecraft:stone", icon,
                List.of(requirement), new StatCondition(List.of(requirement), 1000L),
                List.of(RewardDefinition.currency("stone_coins", 500L),
                        RewardDefinition.title("stone_title", "geologist"),
                        RewardDefinition.titleTimed("stone_architect_7d", "architect", 7,
                                TimedEntitlement.RenewalPolicy.MAX)));
        return new AchievementConfig(List.of(definition));
    }

    private static void write(AchievementConfig config) {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", CURRENT_FORMAT_VERSION);
            JsonObject scheduler = new JsonObject();
            scheduler.addProperty("check_interval_ticks", config.scheduler.checkIntervalTicks());
            scheduler.addProperty("max_players_per_tick", config.scheduler.maxPlayersPerTick());
            scheduler.addProperty("max_conditions_per_tick", config.scheduler.maxConditionsPerTick());
            scheduler.addProperty("full_recheck_seconds", config.scheduler.fullRecheckSeconds());
            root.add("check_scheduler", scheduler);
            JsonArray achievements = new JsonArray();
            for (AchievementDefinition definition : config.achievements) {
                JsonObject achievement = new JsonObject();
                achievement.addProperty("id", definition.id());
                achievement.addProperty("display", definition.display());
                achievement.addProperty("description", definition.description());
                achievement.addProperty("icon", definition.iconId());
                JsonObject condition = new JsonObject();
                condition.addProperty("type", "stat");
                condition.addProperty("stat", definition.requirements().get(0).type().serializedName());
                JsonArray targets = new JsonArray();
                definition.requirements().forEach(requirement -> targets.add(requirement.targetId()));
                condition.add("targets", targets);
                condition.addProperty("match", "sum");
                condition.addProperty("at_least", definition.conditionThreshold());
                achievement.add("requirements", condition);
                JsonArray rewards = new JsonArray();
                for (RewardDefinition reward : definition.rewards()) {
                    rewards.add(reward.toJsonObject());
                }
                achievement.add("rewards", rewards);
                achievements.add(achievement);
            }
            root.add("achievements", achievements);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            System.out.println("[omnitools] Created default achievement config at " + FILE);
        } catch (IOException exception) {
            System.err.println("[omnitools] Could not create default achievement config at " + FILE + ": "
                    + exception.getMessage());
        }
    }

    private static Item resolveItem(String value, String context) {
        Identifier id = parseIdentifier(value, context);
        return BuiltInRegistries.ITEM.get(id).map(holder -> holder.value())
                .orElseThrow(() -> new JsonParseException("Unknown item " + id + " for " + context));
    }

    private static Block resolveBlock(String value, String context) {
        Identifier id = parseIdentifier(value, context);
        return BuiltInRegistries.BLOCK.get(id).map(holder -> holder.value())
                .orElseThrow(() -> new JsonParseException("Unknown block " + id + " for " + context));
    }

    private static EntityType<?> resolveEntityType(String value, String context) {
        Identifier id = parseIdentifier(value, context);
        return BuiltInRegistries.ENTITY_TYPE.get(id).map(holder -> holder.value())
                .orElseThrow(() -> new JsonParseException("Unknown entity type " + id + " for " + context));
    }

    private static Identifier resolveCustomStat(String value, String context) {
        Identifier id = parseIdentifier(value, context);
        return BuiltInRegistries.CUSTOM_STAT.get(id)
                .map(holder -> holder.value())
                .orElseThrow(() -> new JsonParseException("Unknown custom statistic " + id + " for " + context));
    }

    private static long convertCustomThreshold(String customStat, long value, String unit) {
        long multiplier = customUnitMultiplier(customStat, unit);
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException exception) {
            throw new JsonParseException("Custom statistic threshold is too large: " + value + " " + unit);
        }
    }

    private static long customUnitMultiplier(String customStat, String unit) {
        Identifier id = parseIdentifier(customStat, "custom_stat");
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        String path = id.getPath();
        long multiplier;
        if (path.endsWith("_one_cm")) {
            multiplier = switch (normalized) {
                case "cm" -> 1L;
                case "meters", "blocks" -> 100L;
                case "kilometers" -> 100_000L;
                default -> throw new JsonParseException("Unit " + unit
                        + " is not valid for distance statistic " + customStat);
            };
        } else if (path.equals("play_time") || path.equals("total_world_time")) {
            multiplier = switch (normalized) {
                case "ticks" -> 1L;
                case "seconds" -> 20L;
                case "minutes" -> 1_200L;
                case "hours" -> 72_000L;
                default -> throw new JsonParseException("Unit " + unit
                        + " is not valid for time statistic " + customStat);
            };
        } else if (path.startsWith("damage_")) {
            multiplier = switch (normalized) {
                case "damage" -> 10L;
                case "hearts" -> 20L;
                default -> throw new JsonParseException("Unit " + unit
                        + " is not valid for damage statistic " + customStat);
            };
        } else if (!normalized.equals("count")) {
            throw new JsonParseException("Unit " + unit + " is only valid for distance, time or damage statistics");
        } else {
            multiplier = 1L;
        }
        return multiplier;
    }

    private static String displayUnit(String unit) {
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("count") ? "" : normalized;
    }

    /** Distance requirements are always shown in meters, while their configured unit still controls the threshold. */
    private static long progressDisplayDivisor(String customStat, String configuredUnit) {
        return unitFor(RequirementType.CUSTOM, customStat) == StatisticUnit.DISTANCE
                ? 100L : customUnitMultiplier(customStat, configuredUnit);
    }

    private static String progressDisplayUnit(String customStat, String configuredUnit) {
        return unitFor(RequirementType.CUSTOM, customStat) == StatisticUnit.DISTANCE
                ? "meters" : displayUnit(configuredUnit);
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

    private static String optionalString(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(key + " must be a string");
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

    private static int rangedInteger(JsonObject object, String key, int fallback, int minimum, int maximum) {
        int value = integer(object, key, fallback);
        if (value < minimum || value > maximum) {
            throw new JsonParseException(key + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, List<String>> parseTargetGroups(JsonElement element) {
        if (element == null) {
            return Map.of();
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException("target_groups must be an object");
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String name = normalizeId(entry.getKey());
            if (!ID_PATTERN.matcher(name).matches()) {
                throw new JsonParseException("Invalid target group name: " + entry.getKey());
            }
            if (!entry.getValue().isJsonArray() || entry.getValue().getAsJsonArray().isEmpty()) {
                throw new JsonParseException("Target group $" + name + " must be a non-empty string array");
            }
            List<String> targets = new ArrayList<>();
            for (JsonElement target : entry.getValue().getAsJsonArray()) {
                if (!target.isJsonPrimitive() || !target.getAsJsonPrimitive().isString()
                        || target.getAsString().isBlank()) {
                    throw new JsonParseException("Target group $" + name + " must contain non-empty strings");
                }
                targets.add(target.getAsString().trim());
            }
            if (groups.put(name, List.copyOf(targets)) != null) {
                throw new JsonParseException("Target group $" + name + " is configured more than once");
            }
        }
        return Map.copyOf(groups);
    }

    private static void validateTargetGroupGraph(Map<String, List<String>> groups) {
        for (String group : groups.keySet()) {
            validateTargetGroup(group, groups, new java.util.HashSet<>());
        }
    }

    private static void validateTargetGroup(String group, Map<String, List<String>> groups,
                                            java.util.Set<String> visiting) {
        if (!visiting.add(group)) {
            throw new JsonParseException("Circular target group reference involving $" + group);
        }
        for (String target : groups.getOrDefault(group, List.of())) {
            if (target.startsWith("$")) {
                String nested = normalizeId(target.substring(1));
                if (!groups.containsKey(nested)) {
                    throw new JsonParseException("Unknown target group $" + nested + " referenced by $" + group);
                }
                validateTargetGroup(nested, groups, visiting);
            }
        }
        visiting.remove(group);
    }

    private static StatisticUnit unitFor(RequirementType type, String targetId) {
        if (type != RequirementType.CUSTOM) {
            return StatisticUnit.COUNT;
        }
        Identifier id = parseIdentifier(targetId, "custom_stat");
        String path = id.getPath();
        if (path.endsWith("_one_cm")) {
            return StatisticUnit.DISTANCE;
        }
        if (path.equals("play_time") || path.equals("total_world_time")) {
            return StatisticUnit.TIME;
        }
        if (path.startsWith("damage_")) {
            return StatisticUnit.DAMAGE;
        }
        return StatisticUnit.COUNT;
    }

    public enum RequirementType {
        BLOCK_MINED("block_mined", "gui.omnitools.achievement.requirement.block_mined", Domain.BLOCK),
        ITEM_CRAFTED("item_crafted", "gui.omnitools.achievement.requirement.item_crafted", Domain.ITEM),
        ITEM_USED("item_used", "gui.omnitools.achievement.requirement.item_used", Domain.ITEM),
        ITEM_BROKEN("item_broken", "gui.omnitools.achievement.requirement.item_broken", Domain.ITEM),
        ITEM_PICKED_UP("item_picked_up", "gui.omnitools.achievement.requirement.item_picked_up", Domain.ITEM),
        ITEM_DROPPED("item_dropped", "gui.omnitools.achievement.requirement.item_dropped", Domain.ITEM),
        ENTITY_KILLED("entity_killed", "gui.omnitools.achievement.requirement.entity_killed", Domain.ENTITY),
        ENTITY_KILLED_BY("entity_killed_by", "gui.omnitools.achievement.requirement.entity_killed_by", Domain.ENTITY),
        CUSTOM("custom", "gui.omnitools.achievement.requirement.custom", Domain.CUSTOM);

        private final String serializedName;
        private final String translationKey;
        private final Domain domain;

        RequirementType(String serializedName, String translationKey, Domain domain) {
            this.serializedName = serializedName;
            this.translationKey = translationKey;
            this.domain = domain;
        }

        public String serializedName() {
            return serializedName;
        }

        public String translationKey() {
            return translationKey;
        }

        public Domain domain() {
            return domain;
        }

        public static RequirementType parse(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (RequirementType type : values()) {
                if (type.serializedName.equals(normalized)) {
                    return type;
                }
            }
            throw new JsonParseException("Unknown achievement requirement type: " + value);
        }
    }

    public enum Domain {
        BLOCK,
        ITEM,
        ENTITY,
        CUSTOM
    }

    public record SchedulerConfig(int checkIntervalTicks, int maxPlayersPerTick, int maxConditionsPerTick,
                                  int fullRecheckSeconds) {
        public SchedulerConfig {
            if (checkIntervalTicks < 1 || maxPlayersPerTick < 1 || maxConditionsPerTick < 1
                    || fullRecheckSeconds < 1) {
                throw new IllegalArgumentException("Achievement scheduler values must be positive");
            }
        }

        public static SchedulerConfig defaults() {
            return new SchedulerConfig(10, 8, 128, 60);
        }
    }

    public record Requirement(RequirementType type, String targetId, long count, Object target,
                              long multiplier) {
        public Requirement(RequirementType type, String targetId, long count, Object target) {
            this(type, targetId, count, target, 1L);
        }

        public Requirement {
            if (multiplier < 1L) {
                throw new IllegalArgumentException("Requirement multiplier must be positive");
            }
        }
        public long current(net.minecraft.server.level.ServerPlayer player) {
            return current(new StatisticEvaluationContext(player));
        }

        public long current(StatisticEvaluationContext context) {
            return context.value(this);
        }

        public net.minecraft.stats.Stat<?> stat() {
            return switch (type) {
                case BLOCK_MINED -> Stats.BLOCK_MINED.get((Block) target);
                case ITEM_CRAFTED -> Stats.ITEM_CRAFTED.get((Item) target);
                case ITEM_USED -> Stats.ITEM_USED.get((Item) target);
                case ITEM_BROKEN -> Stats.ITEM_BROKEN.get((Item) target);
                case ITEM_PICKED_UP -> Stats.ITEM_PICKED_UP.get((Item) target);
                case ITEM_DROPPED -> Stats.ITEM_DROPPED.get((Item) target);
                case ENTITY_KILLED -> Stats.ENTITY_KILLED.get((EntityType<?>) target);
                case ENTITY_KILLED_BY -> Stats.ENTITY_KILLED_BY.get((EntityType<?>) target);
                case CUSTOM -> Stats.CUSTOM.get((Identifier) target);
            };
        }
    }

    public record AchievementDefinition(String id, String display, String description, String iconId, Item icon,
                                        List<Requirement> requirements, AchievementCondition condition,
                                        List<RewardDefinition> rewards) {
        public AchievementDefinition {
            requirements = List.copyOf(requirements);
            rewards = List.copyOf(rewards == null ? List.of() : rewards);
            if (condition == null) {
                throw new IllegalArgumentException("Achievement condition cannot be null");
            }
        }

        public boolean complete(StatisticEvaluationContext context) {
            return condition.evaluate(context);
        }

        public dev.modmind.omnitools.achievement.ConditionProgress progress(StatisticEvaluationContext context) {
            return condition.progress(context);
        }

        public long conditionThreshold() {
            if (condition instanceof StatCondition stat) {
                return stat.atLeast();
            }
            if (condition instanceof SumCondition sum) {
                return sum.atLeast();
            }
            return requirements.stream().mapToLong(Requirement::count).sum();
        }
    }

    private record ParsedCondition(List<Requirement> requirements, AchievementCondition condition,
                                   boolean hasPositiveStat) {
    }

    private record ParsedNode(List<Requirement> requirements, AchievementCondition condition,
                              boolean hasPositiveStat) {
    }

    private static final class ParseState {
        private final Map<String, List<String>> targetGroups;
        private int leaves;

        private ParseState(Map<String, List<String>> targetGroups) {
            this.targetGroups = targetGroups;
        }

        private void addLeaf(String achievementId) {
            if (++leaves > MAX_CONDITION_LEAVES) {
                throw new JsonParseException("Achievement " + achievementId
                        + " contains more than " + MAX_CONDITION_LEAVES + " statistic conditions");
            }
        }
    }
}
