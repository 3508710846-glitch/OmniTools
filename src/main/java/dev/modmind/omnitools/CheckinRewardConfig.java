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

/** Daily and monthly rewards. Version 2 uses stable, idempotent reward definitions. */
public final class CheckinRewardConfig {
    public static final String FILE_NAME = "omnitools-rewards.json";
    public static final int CURRENT_FORMAT_VERSION = 2;
    public static final List<Integer> MONTHLY_MILESTONES = List.of(5, 10, 15, 25);
    /** Legacy slot bound retained for reading old day:slot online-reward claim keys. */
    public static final int ONLINE_TIME_REWARD_COUNT = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.DAILY_CHECKIN);

    private final List<RewardDefinition> dailyRewards;
    private final Map<Integer, List<RewardDefinition>> monthlyRewards;
    private final List<OnlineTimeReward> onlineTimeRewards;

    private CheckinRewardConfig(List<RewardDefinition> dailyRewards,
                                Map<Integer, List<RewardDefinition>> monthlyRewards,
                                List<OnlineTimeReward> onlineTimeRewards) {
        this.dailyRewards = List.copyOf(dailyRewards);
        Map<Integer, List<RewardDefinition>> copy = new LinkedHashMap<>();
        monthlyRewards.forEach((milestone, rewards) -> copy.put(milestone, List.copyOf(rewards)));
        this.monthlyRewards = Map.copyOf(copy);
        this.onlineTimeRewards = List.copyOf(onlineTimeRewards);
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

    public static CheckinRewardConfig empty() {
        return new CheckinRewardConfig(List.of(), Map.of(), List.of());
    }

    public static CheckinRewardConfig withOnlineRewards(CheckinRewardConfig daily, OnlineRewardConfig online) {
        List<OnlineTimeReward> rewards = online.rewards().stream()
                .map(reward -> new OnlineTimeReward(reward.id(), reward.minutes(), reward.coins()))
                .toList();
        return new CheckinRewardConfig(daily.dailyRewards, daily.monthlyRewards, rewards);
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
        return parseLegacy(root);
    }

    private static CheckinRewardConfig parseV2(JsonObject root, HolderLookup.Provider registries) {
        int version = integer(root, "format_version", CURRENT_FORMAT_VERSION);
        if (version != CURRENT_FORMAT_VERSION) {
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
        return new CheckinRewardConfig(dailyRewards, monthlyRewards, parseOnlineTimeRewards(root));
    }

    private static CheckinRewardConfig parseLegacy(JsonObject root) {
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
        return new CheckinRewardConfig(dailyRewards, monthlyRewards, parseOnlineTimeRewards(root));
    }

    private static List<OnlineTimeReward> parseOnlineTimeRewards(JsonObject root) {
        JsonElement element = root.get("onlineTimeRewards");
        if (element == null) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new JsonParseException("onlineTimeRewards must be an array");
        }
        JsonArray array = element.getAsJsonArray();
        List<OnlineTimeReward> rewards = new ArrayList<>();
        int previousMinutes = 0;
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isJsonObject()) {
                throw new JsonParseException("onlineTimeRewards[" + index + "] must be an object");
            }
            JsonObject reward = array.get(index).getAsJsonObject();
            int minutes = positiveInt(reward, "minutes");
            long coins = nonNegativeLong(reward, "coins", 0L);
            if (minutes <= previousMinutes) {
                throw new JsonParseException("onlineTimeRewards must be ordered by distinct minutes");
            }
            String id = reward.has("id") ? requiredString(reward, "id") : "online_" + minutes + "m";
            rewards.add(new OnlineTimeReward(id.trim().toLowerCase(Locale.ROOT), minutes, coins));
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
        return new CheckinRewardConfig(List.of(RewardDefinition.currency("daily_currency", 100L)), monthly,
                List.of(new OnlineTimeReward("online_30m", 30, 50L),
                        new OnlineTimeReward("online_60m", 60, 100L),
                        new OnlineTimeReward("online_120m", 120, 250L)));
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
            JsonObject object = new JsonObject();
            object.addProperty("id", reward.id());
            object.addProperty("type", reward.type().serializedName());
            if (reward.type() == dev.modmind.omnitools.reward.RewardType.CURRENCY) {
                object.addProperty("amount", reward.amount());
            }
            array.add(object);
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

    public record OnlineTimeReward(String id, int minutes, long coins) {
        public OnlineTimeReward(int minutes, long coins) {
            this("online_" + minutes + "m", minutes, coins);
        }
    }
}
