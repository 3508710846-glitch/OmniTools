package dev.modmind.qiandao;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the server-side reward file and preserves a usable default when it is missing or malformed. */
public final class CheckinRewardConfig {
    public static final String FILE_NAME = "qiandao-rewards.json";
    public static final int MAX_ITEM_COUNT = 256;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

    private final List<RewardEntry> dailyRewards;
    private final Map<Integer, List<RewardEntry>> streakRewards;

    private CheckinRewardConfig(List<RewardEntry> dailyRewards, Map<Integer, List<RewardEntry>> streakRewards) {
        this.dailyRewards = List.copyOf(dailyRewards);
        Map<Integer, List<RewardEntry>> copiedRewards = new LinkedHashMap<>();
        streakRewards.forEach((days, rewards) -> copiedRewards.put(days, List.copyOf(rewards)));
        this.streakRewards = Map.copyOf(copiedRewards);
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
            return parse(root.getAsJsonObject());
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            System.err.println("[qiandao] Could not load " + FILE + ": " + exception.getMessage()
                    + ". Using built-in rewards for this server session.");
            return defaults();
        }
    }

    public List<RewardEntry> dailyRewards() {
        return dailyRewards;
    }

    public List<RewardEntry> streakRewards(int streakDays) {
        return streakRewards.getOrDefault(streakDays, List.of());
    }

    public static Path path() {
        return FILE;
    }

    private static CheckinRewardConfig parse(JsonObject root) {
        List<RewardEntry> daily = parseRewardList(root.get("dailyRewards"), "dailyRewards");
        Map<Integer, List<RewardEntry>> streak = new LinkedHashMap<>();
        JsonElement streakElement = root.get("streakRewards");
        if (streakElement != null && !streakElement.isJsonObject()) {
            throw new JsonParseException("streakRewards must be an object");
        }
        if (streakElement != null) {
            for (Map.Entry<String, JsonElement> entry : streakElement.getAsJsonObject().entrySet()) {
                int days;
                try {
                    days = Integer.parseInt(entry.getKey());
                } catch (NumberFormatException exception) {
                    throw new JsonParseException("Invalid streak reward day: " + entry.getKey());
                }
                if (days <= 0) {
                    throw new JsonParseException("Streak reward day must be positive: " + entry.getKey());
                }
                streak.put(days, parseRewardList(entry.getValue(), "streakRewards." + entry.getKey()));
            }
        }
        return new CheckinRewardConfig(daily, streak);
    }

    private static List<RewardEntry> parseRewardList(JsonElement element, String name) {
        if (element == null) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new JsonParseException(name + " must be an array");
        }

        List<RewardEntry> rewards = new ArrayList<>();
        for (JsonElement rewardElement : element.getAsJsonArray()) {
            if (!rewardElement.isJsonObject()) {
                throw new JsonParseException(name + " entries must be objects");
            }
            JsonObject reward = rewardElement.getAsJsonObject();
            String type = requiredString(reward, "type", name);
            switch (type) {
                case "item" -> {
                    String item = requiredString(reward, "item", name);
                    int count = optionalPositiveInt(reward, "count", 1, name);
                    rewards.add(RewardEntry.item(item, count));
                }
                case "command" -> rewards.add(RewardEntry.command(requiredString(reward, "command", name)));
                default -> throw new JsonParseException(name + " has unsupported reward type: " + type);
            }
        }
        return rewards;
    }

    private static String requiredString(JsonObject object, String key, String scope) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || element.getAsString().isBlank()) {
            throw new JsonParseException(scope + " requires a non-empty " + key);
        }
        return element.getAsString();
    }

    private static int optionalPositiveInt(JsonObject object, String key, int defaultValue, String scope) {
        JsonElement element = object.get(key);
        if (element == null) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(scope + "." + key + " must be an integer");
        }
        int value;
        try {
            value = new BigDecimal(element.getAsString()).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new JsonParseException(scope + "." + key + " must be an integer");
        }
        if (value <= 0) {
            throw new JsonParseException(scope + "." + key + " must be positive");
        }
        if (value > MAX_ITEM_COUNT) {
            throw new JsonParseException(scope + "." + key + " must not exceed " + MAX_ITEM_COUNT);
        }
        return value;
    }

    private static CheckinRewardConfig defaults() {
        Map<Integer, List<RewardEntry>> streak = new LinkedHashMap<>();
        streak.put(3, List.of(RewardEntry.item("minecraft:iron_ingot", 3)));
        streak.put(5, List.of(RewardEntry.item("minecraft:gold_ingot", 3)));
        streak.put(10, List.of(RewardEntry.item("minecraft:diamond", 2)));
        streak.put(15, List.of(RewardEntry.item("minecraft:emerald", 8)));
        streak.put(25, List.of(RewardEntry.item("minecraft:netherite_scrap", 2)));
        return new CheckinRewardConfig(List.of(RewardEntry.item("minecraft:bread", 2)), streak);
    }

    private static void write(CheckinRewardConfig config) {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.add("dailyRewards", toJson(config.dailyRewards));
            JsonObject streak = new JsonObject();
            config.streakRewards.forEach((days, rewards) -> streak.add(Integer.toString(days), toJson(rewards)));
            root.add("streakRewards", streak);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            System.out.println("[qiandao] Created default reward config at " + FILE);
        } catch (IOException exception) {
            System.err.println("[qiandao] Could not create default reward config at " + FILE + ": "
                    + exception.getMessage());
        }
    }

    private static JsonArray toJson(List<RewardEntry> rewards) {
        JsonArray array = new JsonArray();
        for (RewardEntry reward : rewards) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", reward.type());
            if (reward.isItem()) {
                entry.addProperty("item", reward.itemId());
                entry.addProperty("count", reward.count());
            } else {
                entry.addProperty("command", reward.command());
            }
            array.add(entry);
        }
        return array;
    }

    public record RewardEntry(String type, String itemId, int count, String command) {
        static RewardEntry item(String itemId, int count) {
            return new RewardEntry("item", itemId, count, "");
        }

        static RewardEntry command(String command) {
            return new RewardEntry("command", "", 0, command);
        }

        boolean isItem() {
            return type.equals("item");
        }
    }
}
