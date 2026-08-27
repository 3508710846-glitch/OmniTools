package dev.modmind.omnitools.statistics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.AchievementConfig;
import dev.modmind.omnitools.achievement.StatisticTargetResolver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable, resolved vanilla-statistic query shared by achievement conditions and leaderboards.
 * Values always retain their raw vanilla units so sorting never loses precision; {@link #format(long)}
 * applies a display-only unit conversion.
 */
public record StatisticQuery(AchievementConfig.RequirementType type, List<String> targets,
                             Aggregation aggregation, String unit) {
    public StatisticQuery {
        if (type == null || targets == null || targets.isEmpty() || aggregation == null) {
            throw new IllegalArgumentException("A statistic query needs a type, targets and aggregation");
        }
        targets = List.copyOf(targets);
        unit = normalizeUnit(type, targets.getFirst(), unit);
    }

    public static StatisticQuery parse(JsonObject source, Map<String, List<String>> groups, String context) {
        if (source == null) {
            throw new JsonParseException(context + " must be an object");
        }
        String typeName = requiredString(source, "type", context);
        AchievementConfig.RequirementType type = AchievementConfig.RequirementType.parse(typeName);
        Aggregation aggregation = Aggregation.parse(optionalString(source, "aggregation", "sum"));
        String unit = optionalString(source, "unit", "count");
        if (type == AchievementConfig.RequirementType.CUSTOM) {
            if (source.has("targets")) {
                throw new JsonParseException(context + ".custom must use custom_stat instead of targets");
            }
            String customStat = requiredString(source, "custom_stat", context);
            validateTarget(type, customStat, context + ".custom_stat");
            return new StatisticQuery(type, List.of(customStat), aggregation, unit);
        }
        if (source.has("custom_stat")) {
            throw new JsonParseException(context + ".custom_stat is only valid when type is custom");
        }
        JsonElement targetsElement = source.get("targets");
        if (targetsElement == null || !targetsElement.isJsonArray() || targetsElement.getAsJsonArray().isEmpty()) {
            throw new JsonParseException(context + ".targets must be a non-empty array");
        }
        List<String> rawTargets = new ArrayList<>();
        for (JsonElement target : targetsElement.getAsJsonArray()) {
            if (!target.isJsonPrimitive() || !target.getAsJsonPrimitive().isString()) {
                throw new JsonParseException(context + ".targets must contain strings");
            }
            rawTargets.add(target.getAsString().trim());
        }
        List<String> resolved;
        try {
            resolved = StatisticTargetResolver.resolve(type, rawTargets, groups == null ? Map.of() : groups, context);
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(exception.getMessage(), exception);
        }
        for (String target : resolved) {
            validateTarget(type, target, context + ".targets");
        }
        return new StatisticQuery(type, resolved, aggregation, unit);
    }

    /** Uses the exact same expansion path for achievement target lists. */
    public static List<String> resolveTargets(AchievementConfig.RequirementType type, List<String> rawTargets,
                                              Map<String, List<String>> groups, String context) {
        return StatisticTargetResolver.resolve(type, rawTargets, groups, context);
    }

    public long value(StatsCounter stats) {
        long result = aggregation == Aggregation.MIN ? Long.MAX_VALUE : 0L;
        for (String target : targets) {
            long current = Math.max(0L, stats.getValue(stat(type, target)));
            result = switch (aggregation) {
                case SUM -> saturatingAdd(result, current);
                case MIN -> Math.min(result, current);
                case MAX -> Math.max(result, current);
            };
        }
        return aggregation == Aggregation.MIN && result == Long.MAX_VALUE ? 0L : result;
    }

    public String format(long rawValue) {
        long value = Math.max(0L, rawValue);
        long divisor = switch (unit) {
            case "meters", "blocks" -> 100L;
            case "kilometers" -> 100_000L;
            case "seconds" -> 20L;
            case "minutes" -> 1_200L;
            case "hours" -> 72_000L;
            case "hearts" -> 2L;
            default -> 1L;
        };
        long displayed = value / divisor;
        return switch (unit) {
            case "count" -> Long.toString(displayed);
            case "cm" -> displayed + " cm";
            case "meters" -> displayed + " m";
            case "blocks" -> displayed + " blocks";
            case "kilometers" -> displayed + " km";
            case "ticks" -> displayed + " ticks";
            case "seconds" -> displayed + " s";
            case "minutes" -> displayed + " min";
            case "hours" -> displayed + " h";
            case "damage" -> Long.toString(displayed);
            case "hearts" -> displayed + " hearts";
            default -> Long.toString(displayed);
        };
    }

    public static Stat<?> stat(AchievementConfig.RequirementType type, String targetId) {
        Identifier id = Identifier.tryParse(targetId);
        if (id == null) {
            throw new JsonParseException("Invalid statistic target id: " + targetId);
        }
        return switch (type) {
            case BLOCK_MINED -> Stats.BLOCK_MINED.get(required(BuiltInRegistries.BLOCK, id, "block"));
            case ITEM_CRAFTED -> Stats.ITEM_CRAFTED.get(required(BuiltInRegistries.ITEM, id, "item"));
            case ITEM_USED -> Stats.ITEM_USED.get(required(BuiltInRegistries.ITEM, id, "item"));
            case ITEM_BROKEN -> Stats.ITEM_BROKEN.get(required(BuiltInRegistries.ITEM, id, "item"));
            case ITEM_PICKED_UP -> Stats.ITEM_PICKED_UP.get(required(BuiltInRegistries.ITEM, id, "item"));
            case ITEM_DROPPED -> Stats.ITEM_DROPPED.get(required(BuiltInRegistries.ITEM, id, "item"));
            case ENTITY_KILLED -> Stats.ENTITY_KILLED.get(required(BuiltInRegistries.ENTITY_TYPE, id, "entity"));
            case ENTITY_KILLED_BY -> Stats.ENTITY_KILLED_BY.get(required(BuiltInRegistries.ENTITY_TYPE, id, "entity"));
            case CUSTOM -> Stats.CUSTOM.get(required(BuiltInRegistries.CUSTOM_STAT, id, "custom statistic"));
        };
    }

    private static <T> T required(net.minecraft.core.Registry<T> registry, Identifier id, String kind) {
        return registry.getOptional(id).orElseThrow(() -> new JsonParseException("Unknown " + kind + " target: " + id));
    }

    private static void validateTarget(AchievementConfig.RequirementType type, String target, String context) {
        stat(type, target);
    }

    private static String requiredString(JsonObject object, String key, String context) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new JsonParseException(context + "." + key + " must be a non-empty string");
        }
        return value.getAsString().trim();
    }

    private static String optionalString(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(key + " must be a string");
        }
        return value.getAsString().trim();
    }

    private static String normalizeUnit(AchievementConfig.RequirementType type, String target, String requested) {
        String value = requested == null ? "count" : requested.trim().toLowerCase(Locale.ROOT);
        Identifier id = Identifier.tryParse(target);
        String path = id == null ? "" : id.getPath();
        List<String> allowed;
        if (type != AchievementConfig.RequirementType.CUSTOM) {
            allowed = List.of("count");
        } else if (path.endsWith("_one_cm")) {
            allowed = List.of("cm", "meters", "blocks", "kilometers");
        } else if (path.equals("play_time") || path.equals("total_world_time")) {
            allowed = List.of("ticks", "seconds", "minutes", "hours");
        } else if (path.startsWith("damage_")) {
            allowed = List.of("damage", "hearts");
        } else {
            allowed = List.of("count");
        }
        if (!allowed.contains(value)) {
            throw new JsonParseException("Unit " + value + " is not valid for " + target + "; allowed: " + allowed);
        }
        return value;
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    public enum Aggregation {
        SUM, MIN, MAX;

        public static Aggregation parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw new JsonParseException("aggregation must be sum, min, or max");
            }
        }
    }
}
