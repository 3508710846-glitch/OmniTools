package dev.modmind.omnitools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.reward.RewardDefinition;
import dev.modmind.omnitools.entitlement.TimedEntitlement;
import net.minecraft.core.HolderLookup;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Daily, monthly, and virtual makeup-card rules. Version 2 introduced stable reward ids. */
public final class CheckinRewardConfig {
    public static final String FILE_NAME = "omnitools-rewards.json";
    public static final int CURRENT_FORMAT_VERSION = 3;
    public static final List<Integer> MONTHLY_MILESTONES = List.of(5, 10, 15, 25);
    /** Legacy slot bound retained for reading old day:slot online-reward claim keys. */
    public static final int ONLINE_TIME_REWARD_COUNT = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.DAILY_CHECKIN);

    private final List<RewardDefinition> dailyRewards;
    private final Map<Integer, List<RewardDefinition>> monthlyRewards;
    private final List<OnlineTimeReward> onlineTimeRewards;
    private final CheckinUiConfig ui;
    private final MakeupConfig makeup;

    private CheckinRewardConfig(List<RewardDefinition> dailyRewards,
                                Map<Integer, List<RewardDefinition>> monthlyRewards,
                                List<OnlineTimeReward> onlineTimeRewards, CheckinUiConfig ui,
                                MakeupConfig makeup) {
        this.dailyRewards = List.copyOf(dailyRewards);
        Map<Integer, List<RewardDefinition>> copy = new LinkedHashMap<>();
        monthlyRewards.forEach((milestone, rewards) -> copy.put(milestone, List.copyOf(rewards)));
        this.monthlyRewards = Map.copyOf(copy);
        this.onlineTimeRewards = List.copyOf(onlineTimeRewards);
        this.ui = ui == null ? CheckinUiConfig.defaults() : ui;
        this.makeup = makeup == null ? MakeupConfig.defaults() : makeup;
    }

    public static CheckinRewardConfig load(HolderLookup.Provider registries) {
        if (!Files.exists(FILE)) {
            CheckinRewardConfig defaults = defaults();
            write(defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                throw new JsonParseException("Root value must be an object");
            }
            return parse(root.getAsJsonObject(), registries);
        } catch (IOException | RuntimeException exception) {
            System.err.println("[omnitools] Could not load " + FILE + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid daily check-in configuration", exception);
        }
    }

    public List<RewardDefinition> dailyRewards() {
        return dailyRewards;
    }

    public Map<Integer, List<RewardDefinition>> monthlyRewards() {
        return monthlyRewards;
    }

    public List<OnlineTimeReward> onlineTimeRewards() {
        return onlineTimeRewards;
    }

    public CheckinUiConfig ui() {
        return ui;
    }

    public MakeupConfig makeup() {
        return makeup;
    }

    public static CheckinRewardConfig empty() {
        return new CheckinRewardConfig(List.of(), Map.of(), List.of(), CheckinUiConfig.defaults(), MakeupConfig.defaults());
    }

    public static CheckinRewardConfig withOnlineRewards(CheckinRewardConfig daily, OnlineRewardConfig online) {
        List<OnlineTimeReward> rewards = online.rewards().stream()
                .map(reward -> new OnlineTimeReward(reward.id(), reward.minutes(), reward.rewards()))
                .toList();
        return new CheckinRewardConfig(daily.dailyRewards, daily.monthlyRewards, rewards, daily.ui, daily.makeup);
    }

    public static Path path() {
        return FILE;
    }

    private static CheckinRewardConfig parse(JsonObject root, HolderLookup.Provider registries) {
        boolean hasLegacyFields = root.has("dailyCoins") || root.has("dailyReward") || root.has("monthlyRewards")
                || root.has("monthlyCoins") || (root.has("daily") && root.get("daily").isJsonPrimitive());
        boolean modern = !hasLegacyFields && ((root.has("daily") && root.get("daily").isJsonObject())
                || (root.has("monthly") && root.get("monthly").isJsonObject())
                || integer(root, "format_version", 1) >= 2);
        if (modern) {
            return parseV2(root, registries);
        }
        return parseLegacy(root, registries);
    }

    private static CheckinRewardConfig parseV2(JsonObject root, HolderLookup.Provider registries) {
        int version = integer(root, "format_version", CURRENT_FORMAT_VERSION);
        if (version != 2 && version != CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("Unsupported daily check-in format_version: " + version);
        }
        JsonObject daily = requiredObject(root, "daily");
        List<RewardDefinition> dailyRewards = RewardDefinition.parseArray(daily.get("rewards"), "daily.rewards", registries);
        JsonObject monthly = requiredObject(root, "monthly");
        Map<Integer, List<RewardDefinition>> monthlyRewards = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : monthly.entrySet()) {
            int milestone = positiveMilestone(entry.getKey());
            if (monthlyRewards.put(milestone, RewardDefinition.parseArray(entry.getValue(),
                    "monthly." + entry.getKey(), registries)) != null) {
                throw new JsonParseException("monthly contains duplicate milestone " + milestone);
            }
        }
        CheckinUiConfig ui = CheckinUiConfig.parse(root);
        ui.validateItems();
        return new CheckinRewardConfig(dailyRewards, monthlyRewards, parseOnlineTimeRewards(root, registries), ui,
                MakeupConfig.parse(root));
    }

    private static CheckinRewardConfig parseLegacy(JsonObject root, HolderLookup.Provider registries) {
        long daily = nonNegativeLong(root, "dailyCoins", "dailyReward", "daily", defaultsDailyCoins());
        List<RewardDefinition> dailyRewards = daily == 0L ? List.of()
                : List.of(RewardDefinition.currency("legacy_daily_currency", daily));
        JsonElement monthlyElement = root.has("monthlyRewards") ? root.get("monthlyRewards") : root.get("monthlyCoins");
        if (monthlyElement != null && !monthlyElement.isJsonObject()) {
            throw new JsonParseException("monthlyRewards must be an object");
        }
        JsonObject monthlyObject = monthlyElement == null ? new JsonObject() : monthlyElement.getAsJsonObject();
        Map<Integer, List<RewardDefinition>> monthlyRewards = new LinkedHashMap<>();
        for (int milestone : MONTHLY_MILESTONES) {
            long coins = nonNegativeLong(monthlyObject, Integer.toString(milestone), defaultsMonthlyCoins(milestone));
            monthlyRewards.put(milestone, coins == 0L ? List.of()
                    : List.of(RewardDefinition.currency("legacy_monthly_" + milestone + "_currency", coins)));
        }
        CheckinUiConfig ui = CheckinUiConfig.parse(root);
        ui.validateItems();
        return new CheckinRewardConfig(dailyRewards, monthlyRewards, parseOnlineTimeRewards(root, registries), ui,
                MakeupConfig.defaults());
    }

    private static List<OnlineTimeReward> parseOnlineTimeRewards(JsonObject root, HolderLookup.Provider registries) {
        JsonElement element = root.get("onlineTimeRewards");
        if (element == null) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new JsonParseException("onlineTimeRewards must be an array");
        }
        JsonArray array = element.getAsJsonArray();
        List<OnlineTimeReward> rewards = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        int previousMinutes = 0;
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isJsonObject()) {
                throw new JsonParseException("onlineTimeRewards[" + index + "] must be an object");
            }
            JsonObject reward = array.get(index).getAsJsonObject();
            int minutes = positiveInt(reward, "minutes");
            if (minutes <= previousMinutes) {
                throw new JsonParseException("onlineTimeRewards must be ordered by distinct minutes");
            }
            String id = reward.has("id") ? requiredString(reward, "id") : "online_" + minutes + "m";
            id = id.trim().toLowerCase(Locale.ROOT);
            if (!RewardDefinition.ID_PATTERN.matcher(id).matches() || !ids.add(id)) {
                throw new JsonParseException("onlineTimeRewards has an invalid or duplicate id: " + id);
            }
            List<RewardDefinition> definitions = reward.has("rewards")
                    ? RewardDefinition.parseArray(reward.get("rewards"), "onlineTimeRewards[" + index + "].rewards",
                    registries)
                    : List.of(RewardDefinition.currency("legacy_currency", nonNegativeLong(reward, "coins", 0L)));
            if (definitions.isEmpty()) {
                throw new JsonParseException("onlineTimeRewards[" + index + "].rewards must not be empty");
            }
            rewards.add(new OnlineTimeReward(id, minutes, definitions));
            previousMinutes = minutes;
        }
        return List.copyOf(rewards);
    }

    private static CheckinRewardConfig defaults() {
        Map<Integer, List<RewardDefinition>> monthly = new LinkedHashMap<>();
        monthly.put(5, List.of(RewardDefinition.currency("month_5_currency", 500L)));
        monthly.put(10, List.of(RewardDefinition.currency("month_10_currency", 1_000L)));
        monthly.put(15, List.of(RewardDefinition.currency("month_15_currency", 2_000L)));
        monthly.put(25, List.of(RewardDefinition.currency("month_25_currency", 5_000L)));
        return new CheckinRewardConfig(List.of(
                        RewardDefinition.currency("daily_currency", 100L),
                        RewardDefinition.titleTimed("daily_architect_7d", "architect", 7,
                                TimedEntitlement.RenewalPolicy.EXTEND)), monthly,
                List.of(new OnlineTimeReward("online_30m", 30,
                                List.of(RewardDefinition.currency("currency", 50L))),
                        new OnlineTimeReward("online_60m", 60,
                                List.of(RewardDefinition.currency("currency", 100L))),
                        new OnlineTimeReward("online_120m", 120,
                                List.of(RewardDefinition.currency("currency", 250L)))), CheckinUiConfig.defaults(),
                MakeupConfig.defaults());
    }

    private static void write(CheckinRewardConfig config) {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", CURRENT_FORMAT_VERSION);
            JsonObject daily = new JsonObject();
            daily.add("rewards", writeRewards(config.dailyRewards));
            root.add("daily", daily);
            JsonObject monthly = new JsonObject();
            config.monthlyRewards.forEach((days, rewards) -> monthly.add(Integer.toString(days), writeRewards(rewards)));
            root.add("monthly", monthly);
            root.add("makeup", config.makeup.toJson());
            CheckinUiConfig.writeDefault(root);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            System.out.println("[omnitools] Created default reward config at " + FILE);
        } catch (IOException exception) {
            System.err.println("[omnitools] Could not create default reward config at " + FILE + ": "
                    + exception.getMessage());
        }
    }

    private static JsonArray writeRewards(List<RewardDefinition> rewards) {
        JsonArray array = new JsonArray();
        for (RewardDefinition reward : rewards) {
            array.add(reward.toJsonObject());
        }
        return array;
    }

    private static int positiveMilestone(String key) {
        try {
            int value = Integer.parseInt(key);
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new JsonParseException("monthly reward milestone must be a positive integer: " + key);
        }
    }

    private static JsonObject requiredObject(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException(key + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new JsonParseException(key + " must be a non-empty string");
        }
        return element.getAsString();
    }

    private static String optionalString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(key + " must be a string");
        }
        return element.getAsString();
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

    private static int positiveInt(JsonObject object, String key) {
        int value = integer(object, key, -1);
        if (value < 1) {
            throw new JsonParseException(key + " must be a positive integer");
        }
        return value;
    }

    private static long nonNegativeLong(JsonObject object, String key, long defaultValue) {
        return nonNegativeLong(object, key, null, null, defaultValue);
    }

    private static long nonNegativeLong(JsonObject object, String key, String alias, String secondAlias,
                                        long defaultValue) {
        JsonElement element = object.get(key);
        String valueName = key;
        if (element == null && alias != null) {
            element = object.get(alias);
            valueName = alias;
        }
        if (element == null && secondAlias != null) {
            element = object.get(secondAlias);
            valueName = secondAlias;
        }
        if (element == null) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(valueName + " must be a non-negative integer");
        }
        try {
            long value = Long.parseLong(element.getAsString());
            if (value < 0L) {
                throw new JsonParseException(valueName + " must be non-negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new JsonParseException(valueName + " must be a non-negative integer");
        }
    }

    private static long defaultsDailyCoins() {
        return 100L;
    }

    private static long defaultsMonthlyCoins(int milestone) {
        return switch (milestone) {
            case 5 -> 500L;
            case 10 -> 1_000L;
            case 15 -> 2_000L;
            case 25 -> 5_000L;
            default -> 0L;
        };
    }

    public record OnlineTimeReward(String id, int minutes, List<RewardDefinition> rewards) {
        public OnlineTimeReward {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            if (!RewardDefinition.ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("Online reward id is invalid: " + id);
            }
            if (minutes < 1) {
                throw new IllegalArgumentException("Online reward minutes must be positive");
            }
            rewards = List.copyOf(rewards == null ? List.of() : rewards);
        }

        public OnlineTimeReward(int minutes, long coins) {
            this("online_" + minutes + "m", minutes,
                    List.of(RewardDefinition.currency("legacy_currency", coins)));
        }
    }

    public enum EarliestEligibleDay {
        FIRST_SEEN;

        static EarliestEligibleDay parse(String value) {
            if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("first_seen")) {
                return FIRST_SEEN;
            }
            throw new JsonParseException("makeup.earliest_eligible_day must be first_seen");
        }

        String serializedName() {
            return "first_seen";
        }
    }

    public enum DailyRewardPolicy {
        NONE,
        GRANT;

        static DailyRewardPolicy parse(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("makeup.daily_reward_policy must be none or grant");
            }
        }

        String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Immutable bounded policy for server-owned cards and historical check-ins. */
    public record MakeupConfig(boolean enabled, int maxCards, int maxBackfillDays, int maxUsesPerCalendarMonth,
                               EarliestEligibleDay earliestEligibleDay, boolean affectsStreak,
                               DailyRewardPolicy dailyRewardPolicy, boolean countsForMonthlyMilestones,
                               PurchaseConfig purchase) {
        public MakeupConfig {
            if (maxCards < 0 || maxCards > 1_000_000) {
                throw new IllegalArgumentException("makeup.max_cards must be between 0 and 1000000");
            }
            if (maxBackfillDays < 1 || maxBackfillDays > 366) {
                throw new IllegalArgumentException("makeup.max_backfill_days must be between 1 and 366");
            }
            if (maxUsesPerCalendarMonth < 1 || maxUsesPerCalendarMonth > 31) {
                throw new IllegalArgumentException("makeup.max_uses_per_calendar_month must be between 1 and 31");
            }
            earliestEligibleDay = earliestEligibleDay == null ? EarliestEligibleDay.FIRST_SEEN : earliestEligibleDay;
            dailyRewardPolicy = dailyRewardPolicy == null ? DailyRewardPolicy.NONE : dailyRewardPolicy;
            purchase = purchase == null ? PurchaseConfig.defaults() : purchase;
        }

        static MakeupConfig defaults() {
            return new MakeupConfig(true, 99, 7, 3, EarliestEligibleDay.FIRST_SEEN, true,
                    DailyRewardPolicy.NONE, true, PurchaseConfig.defaults());
        }

        static MakeupConfig parse(JsonObject root) {
            JsonElement element = root.get("makeup");
            if (element == null) {
                return defaults();
            }
            if (!element.isJsonObject()) {
                throw new JsonParseException("makeup must be an object");
            }
            JsonObject makeup = element.getAsJsonObject();
            MakeupConfig defaults = defaults();
            boolean enabled = booleanValue(makeup, "enabled", defaults.enabled);
            int maxCards = boundedInt(makeup, "max_cards", defaults.maxCards, 0, 1_000_000);
            int maxBackfillDays = boundedInt(makeup, "max_backfill_days", defaults.maxBackfillDays, 1, 366);
            int monthlyUses = boundedInt(makeup, "max_uses_per_calendar_month", defaults.maxUsesPerCalendarMonth, 1, 31);
            EarliestEligibleDay earliest = EarliestEligibleDay.parse(optionalString(makeup, "earliest_eligible_day"));
            boolean affectsStreak = booleanValue(makeup, "affects_streak", defaults.affectsStreak);
            DailyRewardPolicy dailyPolicy = DailyRewardPolicy.parse(optionalString(makeup, "daily_reward_policy"));
            boolean countsMonthly = booleanValue(makeup, "counts_for_monthly_milestones",
                    defaults.countsForMonthlyMilestones);
            return new MakeupConfig(enabled, maxCards, maxBackfillDays, monthlyUses, earliest, affectsStreak,
                    dailyPolicy, countsMonthly, PurchaseConfig.parse(makeup));
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("enabled", enabled);
            result.addProperty("max_cards", maxCards);
            result.addProperty("max_backfill_days", maxBackfillDays);
            result.addProperty("max_uses_per_calendar_month", maxUsesPerCalendarMonth);
            result.addProperty("earliest_eligible_day", earliestEligibleDay.serializedName());
            result.addProperty("affects_streak", affectsStreak);
            result.addProperty("daily_reward_policy", dailyRewardPolicy.serializedName());
            result.addProperty("counts_for_monthly_milestones", countsForMonthlyMilestones);
            result.add("purchase", purchase.toJson());
            return result;
        }
    }

    public record PurchaseConfig(boolean enabled, long price) {
        public PurchaseConfig {
            if (price < 0L) {
                throw new IllegalArgumentException("makeup.purchase.price must be non-negative");
            }
        }

        static PurchaseConfig defaults() {
            return new PurchaseConfig(true, 200L);
        }

        static PurchaseConfig parse(JsonObject makeup) {
            JsonElement element = makeup.get("purchase");
            if (element == null) {
                return defaults();
            }
            if (!element.isJsonObject()) {
                throw new JsonParseException("makeup.purchase must be an object");
            }
            JsonObject purchase = element.getAsJsonObject();
            return new PurchaseConfig(booleanValue(purchase, "enabled", true),
                    nonNegativeLong(purchase, "price", 200L));
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("enabled", enabled);
            result.addProperty("price", price);
            return result;
        }
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException(key + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static int boundedInt(JsonObject object, String key, int fallback, int min, int max) {
        int value = integer(object, key, fallback);
        if (value < min || value > max) {
            throw new JsonParseException(key + " is out of range");
        }
        return value;
    }
}
