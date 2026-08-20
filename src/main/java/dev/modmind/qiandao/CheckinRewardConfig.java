package dev.modmind.qiandao;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

    private final long dailyCoins;
    private final Map<Integer, Long> monthlyRewards;

    private CheckinRewardConfig(long dailyCoins, Map<Integer, Long> monthlyRewards) {
        this.dailyCoins = dailyCoins;
        this.monthlyRewards = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(monthlyRewards));
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
                    + ". Using built-in currency rewards for this server session.");
            return defaults();
        }
    }

    public long dailyCoins() {
        return dailyCoins;
    }

    public Map<Integer, Long> monthlyRewards() {
        return monthlyRewards;
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
        return new CheckinRewardConfig(daily, monthly);
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
        return new CheckinRewardConfig(100L, monthly);
    }

    private static void write(CheckinRewardConfig config) {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
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
}
