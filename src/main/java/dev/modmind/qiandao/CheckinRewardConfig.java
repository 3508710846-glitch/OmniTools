package dev.modmind.qiandao;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import dev.modmind.qiandao.config.ConfigPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-side numeric currency rewards. No reward item is created or delivered. */
public final class CheckinRewardConfig {
    public static final String FILE_NAME = "qiandao-rewards.json";
    public static final List<Integer> MONTHLY_MILESTONES = List.of(5, 10, 15, 25);
    /** Legacy slot bound retained for reading old day:slot claim keys. */
    public static final int ONLINE_TIME_REWARD_COUNT = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigPaths.moduleConfig(dev.modmind.qiandao.config.ModuleId.DAILY_CHECKIN);

    private final long dailyCoins;
    private final Map<Integer, Long> monthlyRewards;
    private final List<OnlineTimeReward> onlineTimeRewards;

    private CheckinRewardConfig(long dailyCoins, Map<Integer, Long> monthlyRewards,
                                List<OnlineTimeReward> onlineTimeRewards) {
        this.dailyCoins = dailyCoins;
        this.monthlyRewards = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(monthlyRewards));
        this.onlineTimeRewards = List.copyOf(onlineTimeRewards);
    }

    public static CheckinRewardConfig load() {
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
            JsonObject object = root.getAsJsonObject();
            CheckinRewardConfig config = parse(object);
            // Replace the pre-currency item/command schema on first load after upgrading.
            if (!object.has("dailyCoins") || !object.has("monthlyRewards")) {
                write(config);
            }
            return config;
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            System.err.println("[qiandao] Could not load " + FILE + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid daily check-in configuration", exception);
        }
    }

    public long dailyCoins() {
        return dailyCoins;
    }

    public Map<Integer, Long> monthlyRewards() {
        return monthlyRewards;
    }

    public List<OnlineTimeReward> onlineTimeRewards() {
        return onlineTimeRewards;
    }

    public static CheckinRewardConfig empty() {
        return new CheckinRewardConfig(0L, Map.of(), List.of());
    }

    public static CheckinRewardConfig withOnlineRewards(CheckinRewardConfig daily, OnlineRewardConfig online) {
        List<OnlineTimeReward> rewards = online.rewards().stream()
                .map(reward -> new OnlineTimeReward(reward.id(), reward.minutes(), reward.coins()))
                .toList();
        return new CheckinRewardConfig(daily.dailyCoins, daily.monthlyRewards, rewards);
    }

    public static Path path() {
        return FILE;
    }

    private static CheckinRewardConfig parse(JsonObject root) {
        long daily = nonNegativeLong(root, "dailyCoins", "dailyReward", "daily", defaults().dailyCoins);
        Map<Integer, Long> monthly = new LinkedHashMap<>();
        JsonElement monthlyElement = root.has("monthlyRewards") ? root.get("monthlyRewards") : root.get("monthlyCoins");
        if (monthlyElement != null && !monthlyElement.isJsonObject()) {
            throw new JsonParseException("monthlyRewards must be an object");
        }
        JsonObject monthlyObject = monthlyElement == null ? new JsonObject() : monthlyElement.getAsJsonObject();
        for (int milestone : MONTHLY_MILESTONES) {
            monthly.put(milestone, nonNegativeLong(monthlyObject, Integer.toString(milestone),
                    defaults().monthlyRewards.get(milestone)));
        }
        return new CheckinRewardConfig(daily, monthly, parseOnlineTimeRewards(root));
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
        List<OnlineTimeReward> rewards = new java.util.ArrayList<>();
        int previousMinutes = 0;
        for (int index = 0; index < array.size(); index++) {
            JsonElement rewardElement = array.get(index);
            if (!rewardElement.isJsonObject()) {
                throw new JsonParseException("onlineTimeRewards[" + index + "] must be an object");
            }
            JsonObject reward = rewardElement.getAsJsonObject();
            int minutes = positiveInt(reward, "minutes");
            long coins = nonNegativeLong(reward, "coins", 0L);
            if (minutes <= previousMinutes) {
                throw new JsonParseException("onlineTimeRewards must be ordered by distinct minutes");
            }
            String id = reward.has("id") ? reward.get("id").getAsString() : "online_" + minutes + "m";
            rewards.add(new OnlineTimeReward(id.trim().toLowerCase(java.util.Locale.ROOT), minutes, coins));
            previousMinutes = minutes;
        }
        return rewards;
    }

    private static int positiveInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be a positive integer");
        }
        try {
            int value = Integer.parseInt(element.getAsString());
            if (value <= 0) {
                throw new JsonParseException(key + " must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new JsonParseException(key + " must be a positive integer");
        }
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

    private static CheckinRewardConfig defaults() {
        Map<Integer, Long> monthly = new LinkedHashMap<>();
        monthly.put(5, 500L);
        monthly.put(10, 1_000L);
        monthly.put(15, 2_000L);
        monthly.put(25, 5_000L);
        return new CheckinRewardConfig(100L, monthly, List.of(
                new OnlineTimeReward("online_30m", 30, 50L),
                new OnlineTimeReward("online_60m", 60, 100L),
                new OnlineTimeReward("online_120m", 120, 250L)));
    }

    private static void write(CheckinRewardConfig config) {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", 1);
            root.addProperty("dailyCoins", config.dailyCoins);
            JsonObject monthly = new JsonObject();
            config.monthlyRewards.forEach((days, coins) -> monthly.addProperty(Integer.toString(days), coins));
            root.add("monthlyRewards", monthly);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            System.out.println("[qiandao] Created default currency reward config at " + FILE);
        } catch (IOException exception) {
            System.err.println("[qiandao] Could not create default reward config at " + FILE + ": "
                    + exception.getMessage());
        }
    }

    public record OnlineTimeReward(String id, int minutes, long coins) {
        public OnlineTimeReward(int minutes, long coins) {
            this("online_" + minutes + "m", minutes, coins);
        }
    }
}
