package dev.modmind.omnitools.divination;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.config.ConfigFieldReporter;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, administrator-managed configuration for the compact daily-sign workflow. */
public record DivinationConfig(int formatVersion, Settings settings, List<SignDefinition> signs) {
    public static final int CURRENT_FORMAT_VERSION = 2;
    public static final int MAX_SIGNS = 256;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public DivinationConfig {
        if (formatVersion != CURRENT_FORMAT_VERSION) throw new JsonParseException("Unsupported divination format_version: " + formatVersion);
        settings = settings == null ? Settings.defaults() : settings;
        signs = List.copyOf(signs == null ? List.of() : signs);
        if (signs.size() < Rank.values().length || signs.size() > MAX_SIGNS) {
            throw new JsonParseException("divination.signs must contain " + Rank.values().length + "-" + MAX_SIGNS + " entries");
        }
        Set<String> ids = new HashSet<>();
        EnumSet<Rank> ranks = EnumSet.noneOf(Rank.class);
        for (SignDefinition sign : signs) {
            if (sign == null || !ids.add(sign.id())) throw new JsonParseException("divination sign ids must be unique");
            ranks.add(sign.rank());
        }
        if (ranks.size() != Rank.values().length) throw new JsonParseException("divination.signs must provide all five ranks");
    }

    public static DivinationConfig empty() {
        return new DivinationConfig(CURRENT_FORMAT_VERSION, Settings.disabled(), defaults().signs());
    }

    public static DivinationConfig load() {
        Path file = path();
        if (!Files.exists(file)) {
            DivinationConfig defaults = defaults();
            try {
                save(defaults);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create divination configuration", exception);
            }
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject()) throw new JsonParseException("divination configuration must be an object");
            return parse(element.getAsJsonObject());
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Invalid divination configuration", exception);
        }
    }

    public static Path path() { return ConfigPaths.moduleConfig(ModuleId.DIVINATION); }

    public SignDefinition sign(String id) {
        if (id == null) return null;
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return signs.stream().filter(sign -> sign.id().equals(normalized)).findFirst().orElse(null);
    }

    public static DivinationConfig defaults() {
        return new DivinationConfig(CURRENT_FORMAT_VERSION, Settings.defaults(), List.of(
                sign("great_misfortune", Rank.GREAT_MISFORTUNE, 8, -0.05D, 0L,
                        "夜雨敲窗急", "孤灯照远行", "收锋先守静", "云散见天明",
                        "今日运势偏弱，稳住节奏便能避开无谓损耗。", "宜整理物资、完成稳妥事务。",
                        "忌冒险远行、冲动消费。", "先完成一件小事，再决定下一步。"),
                sign("misfortune", Rank.MISFORTUNE, 18, -0.03D, 0L,
                        "微风乱归帆", "石径多回环", "放慢三分步", "前路自渐宽",
                        "今日宜守不宜攻，耐心会比速度更有收获。", "宜日常采集、整理与修缮。",
                        "忌久战和高风险交易。", "把目标缩小到一件可完成的事。"),
                sign("middle", Rank.NEUTRAL, 45, 0.00D, 0L,
                        "云开无定色", "溪水自东流", "平常心作伴", "小满亦成秋",
                        "今日平稳，按自己的节奏行动即可。", "宜完成计划内的玩法。",
                        "忌因一时得失改变长期安排。", "选择最想推进的一项进度。"),
                sign("fortune", Rank.FORTUNE, 22, 0.08D, 80L,
                        "晨光铺古道", "新叶满前川", "勤行逢好景", "顺势得心安",
                        "今日有小吉相伴，持续投入更容易得到回报。", "宜顺着运势方向行动。",
                        "忌分散精力、半途而废。", "优先完成今天最重要的目标。"),
                sign("great_fortune", Rank.GREAT_FORTUNE, 7, 0.15D, 300L,
                        "瑞气满长空", "金辉照远峰", "此行多顺遂", "把握正当中",
                        "今日大吉，适合把积累已久的计划付诸行动。", "宜挑战高价值目标、与伙伴同行。",
                        "忌因好运而忽略准备。", "选定方向后果断出发。")));
    }

    private static SignDefinition sign(String id, Rank rank, int weight, double modifier, long currencyReward,
                                       String first, String second, String third, String fourth, String interpretation,
                                       String favorable, String avoid, String advice) {
        return new SignDefinition(id, rank, weight, Theme.RANDOM, List.of(first, second, third, fourth), interpretation,
                favorable, avoid, advice, modifier, currencyReward);
    }

    static DivinationConfig parse(JsonObject root) {
        ConfigFieldReporter.warnUnknown(root, "divination", Set.of("format_version", "settings", "signs"));
        int version = integer(root, "format_version", 1, "divination");
        if (version != 1 && version != CURRENT_FORMAT_VERSION) throw new JsonParseException("Unsupported divination format_version: " + version);
        Settings settings = Settings.parse(object(root, "settings", "divination.settings"));
        JsonElement entries = root.get("signs");
        if (entries == null || !entries.isJsonArray()) throw new JsonParseException("divination.signs must be an array");
        List<SignDefinition> signs = new ArrayList<>();
        JsonArray values = entries.getAsJsonArray();
        for (int index = 0; index < values.size(); index++) {
            if (!values.get(index).isJsonObject()) throw new JsonParseException("divination.signs[" + index + "] must be an object");
            signs.add(SignDefinition.parse(values.get(index).getAsJsonObject(), "divination.signs[" + index + "]"));
        }
        // v1's nine names are accepted and normalized to the five current display ranks.
        return new DivinationConfig(CURRENT_FORMAT_VERSION, settings, signs);
    }

    private static void save(DivinationConfig config) throws IOException {
        Path file = path();
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("format_version", CURRENT_FORMAT_VERSION);
        root.add("settings", config.settings().toJson());
        JsonArray signs = new JsonArray();
        config.signs().forEach(sign -> signs.add(sign.toJson()));
        root.add("signs", signs);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static JsonObject object(JsonObject parent, String key, String context) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonObject()) throw new JsonParseException(context + " must be an object");
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String key, String context) {
        String value = string(object, key, "", context);
        if (value.isBlank()) throw new JsonParseException(context + "." + key + " is required");
        return value;
    }

    private static String string(JsonObject object, String key, String fallback, String context) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new JsonParseException(context + "." + key + " must be a string");
        return value.getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback, String context) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException(context + "." + key + " must be an integer");
        try {
            return Integer.parseInt(value.getAsString());
        } catch (NumberFormatException exception) {
            throw new JsonParseException(context + "." + key + " must be an integer");
        }
    }

    private static long longValue(JsonObject object, String key, long fallback, String context) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException(context + "." + key + " must be an integer");
        try {
            return Long.parseLong(value.getAsString());
        } catch (NumberFormatException exception) {
            throw new JsonParseException(context + "." + key + " must be an integer");
        }
    }

    private static double decimal(JsonObject object, String key, double fallback, String context) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException(context + "." + key + " must be a number");
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw new JsonParseException(context + "." + key + " must be finite");
        return result;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback, String context) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new JsonParseException(context + "." + key + " must be a boolean");
        return value.getAsBoolean();
    }

    public record Settings(boolean enabled, int dailyFreeDraws, int maxDailyDraws, long rerollCost,
                           double omenBuffCap, int historyRetentionDays, int drawCooldownSeconds,
                           boolean broadcastExtremes) {
        public Settings {
            if (dailyFreeDraws != 1) throw new JsonParseException("divination.settings.daily_free_draws must be exactly 1");
            if (maxDailyDraws < dailyFreeDraws || maxDailyDraws > 9) throw new JsonParseException("divination.settings.max_daily_draws must be between 1 and 9");
            if (rerollCost < 0L || (maxDailyDraws > 1 && rerollCost < 1L)) throw new JsonParseException("divination.settings.reroll_cost is invalid");
            if (!Double.isFinite(omenBuffCap) || omenBuffCap < 0.0D || omenBuffCap > 0.50D) throw new JsonParseException("divination.settings.omen_buff_cap must be between 0 and 0.5");
            if (historyRetentionDays < 1 || historyRetentionDays > 365) throw new JsonParseException("divination.settings.history_retention_days must be between 1 and 365");
            if (drawCooldownSeconds < 0 || drawCooldownSeconds > 3600) throw new JsonParseException("divination.settings.draw_cooldown_seconds must be between 0 and 3600");
        }

        static Settings defaults() { return new Settings(true, 1, 3, 250L, 0.15D, 30, 3, true); }
        static Settings disabled() { return new Settings(false, 1, 1, 0L, 0.15D, 30, 3, false); }
        public boolean rerollsEnabled() { return maxDailyDraws > dailyFreeDraws; }

        static Settings parse(JsonObject object) {
            // v1-only fields are tolerated to let existing worlds upgrade without an immediate config rewrite.
            ConfigFieldReporter.warnUnknown(object, "divination.settings", Set.of("enabled", "daily_free_draws",
                    "max_daily_draws", "reroll_cost", "omen_buff_cap", "history_retention_days",
                    "draw_cooldown_seconds", "broadcast_extremes", "replace_existing", "deep_reading",
                    "misfortune_relief"));
            Settings defaults = defaults();
            return new Settings(bool(object, "enabled", defaults.enabled(), "divination.settings"),
                    integer(object, "daily_free_draws", defaults.dailyFreeDraws(), "divination.settings"),
                    integer(object, "max_daily_draws", defaults.maxDailyDraws(), "divination.settings"),
                    longValue(object, "reroll_cost", defaults.rerollCost(), "divination.settings"),
                    decimal(object, "omen_buff_cap", defaults.omenBuffCap(), "divination.settings"),
                    integer(object, "history_retention_days", defaults.historyRetentionDays(), "divination.settings"),
                    integer(object, "draw_cooldown_seconds", defaults.drawCooldownSeconds(), "divination.settings"),
                    bool(object, "broadcast_extremes", defaults.broadcastExtremes(), "divination.settings"));
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("enabled", enabled);
            object.addProperty("daily_free_draws", dailyFreeDraws);
            object.addProperty("max_daily_draws", maxDailyDraws);
            object.addProperty("reroll_cost", rerollCost);
            object.addProperty("omen_buff_cap", omenBuffCap);
            object.addProperty("history_retention_days", historyRetentionDays);
            object.addProperty("draw_cooldown_seconds", drawCooldownSeconds);
            object.addProperty("broadcast_extremes", broadcastExtremes);
            return object;
        }
    }

    public record SignDefinition(String id, Rank rank, int weight, Theme theme, List<String> poem,
                                 String interpretation, String favorable, String avoid, String advice,
                                 double skillXpModifier, long currencyReward) {
        public SignDefinition {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            if (!ID.matcher(id).matches()) throw new JsonParseException("divination sign id is invalid");
            rank = rank == null ? Rank.NEUTRAL : rank;
            if (weight < 1 || weight > 10_000) throw new JsonParseException("divination sign weight must be between 1 and 10000");
            theme = theme == null ? Theme.RANDOM : theme;
            poem = List.copyOf(poem == null ? List.of() : poem);
            if (poem.size() != 4 || poem.stream().anyMatch(line -> line == null || line.isBlank() || line.length() > 64)) throw new JsonParseException("divination sign poem must contain four non-empty lines up to 64 characters");
            interpretation = nonBlank(interpretation, "interpretation");
            favorable = nonBlank(favorable, "favorable");
            avoid = nonBlank(avoid, "avoid");
            advice = nonBlank(advice, "advice");
            if (interpretation.length() > 160 || favorable.length() > 96 || avoid.length() > 96 || advice.length() > 160) throw new JsonParseException("divination sign text exceeds its maximum length");
            if (!Double.isFinite(skillXpModifier) || skillXpModifier < -0.15D || skillXpModifier > 0.50D) throw new JsonParseException("divination sign skill_xp_modifier must be between -0.15 and 0.5");
            if (currencyReward < 0L || currencyReward > 1_000_000_000L) throw new JsonParseException("divination sign currency_reward must be between 0 and 1000000000");
        }

        private static String nonBlank(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) throw new JsonParseException("divination sign " + field + " is required");
            return normalized;
        }

        static SignDefinition parse(JsonObject object, String context) {
            ConfigFieldReporter.warnUnknown(object, context, Set.of("id", "rank", "weight", "theme", "poem",
                    "interpretation", "favorable", "avoid", "advice", "skill_xp_modifier", "currency_reward",
                    "guidance", "xp_bonus", "relief_goal", "relief_bonus"));
            JsonElement poemElement = object.get("poem");
            if (poemElement == null || !poemElement.isJsonArray()) throw new JsonParseException(context + ".poem must be an array");
            List<String> poem = new ArrayList<>();
            for (JsonElement line : poemElement.getAsJsonArray()) {
                if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) throw new JsonParseException(context + ".poem must contain strings");
                poem.add(line.getAsString());
            }
            String legacyGuidance = string(object, "guidance", "按自己的节奏前行。", context);
            return new SignDefinition(requiredString(object, "id", context), Rank.parse(requiredString(object, "rank", context)),
                    integer(object, "weight", 0, context), Theme.parse(string(object, "theme", "random", context)), poem,
                    requiredString(object, "interpretation", context), string(object, "favorable", legacyGuidance, context),
                    string(object, "avoid", "忌急于求成。", context), string(object, "advice", legacyGuidance, context),
                    decimal(object, "skill_xp_modifier", decimal(object, "xp_bonus", 0.0D, context), context),
                    longValue(object, "currency_reward", 0L, context));
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("id", id);
            object.addProperty("rank", rank.serializedName());
            object.addProperty("weight", weight);
            object.addProperty("theme", theme.serializedName());
            JsonArray lines = new JsonArray();
            poem.forEach(lines::add);
            object.add("poem", lines);
            object.addProperty("interpretation", interpretation);
            object.addProperty("favorable", favorable);
            object.addProperty("avoid", avoid);
            object.addProperty("advice", advice);
            object.addProperty("skill_xp_modifier", skillXpModifier);
            object.addProperty("currency_reward", currencyReward);
            return object;
        }
    }

    public enum Rank {
        GREAT_MISFORTUNE("lower_lower", false, true), MISFORTUNE("lower", false, false),
        NEUTRAL("middle", false, false), FORTUNE("upper", true, false),
        GREAT_FORTUNE("upper_upper", true, true);

        private final String serializedName;
        private final boolean auspicious;
        private final boolean extreme;
        Rank(String serializedName, boolean auspicious, boolean extreme) {
            this.serializedName = serializedName;
            this.auspicious = auspicious;
            this.extreme = extreme;
        }
        public String serializedName() { return serializedName; }
        public boolean auspicious() { return auspicious; }
        public boolean extreme() { return extreme; }
        /** Kept for source compatibility; the compact five-rank model has no relief task. */
        public boolean requiresRelief() { return false; }

        static Rank parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "upper_upper" -> GREAT_FORTUNE;
                case "upper", "upper_middle", "upper_lower" -> FORTUNE;
                case "middle", "middle_upper", "middle_middle", "middle_lower" -> NEUTRAL;
                case "lower", "lower_upper", "lower_middle" -> MISFORTUNE;
                case "lower_lower" -> GREAT_MISFORTUNE;
                default -> throw new JsonParseException("divination sign rank is invalid");
            };
        }
    }

    public enum Theme {
        RANDOM("random"), MINING("mining"), COMBAT("combat"), EXPLORATION("exploration"),
        TRADE("trade"), SOCIAL("social");
        private final String serializedName;
        Theme(String serializedName) { this.serializedName = serializedName; }
        public String serializedName() { return serializedName; }
        static Theme parse(String value) {
            for (Theme theme : values()) if (theme.serializedName.equalsIgnoreCase(value == null ? "" : value.trim())) return theme;
            throw new JsonParseException("divination theme is invalid");
        }
    }
}
