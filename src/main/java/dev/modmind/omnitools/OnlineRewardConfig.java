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
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Stable-ID online reward definitions. */
public final class OnlineRewardConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Path FILE = ConfigPaths.moduleConfig(ModuleId.ONLINE_REWARD);
    private final List<Reward> rewards;

    private OnlineRewardConfig(List<Reward> rewards) {
        this.rewards = List.copyOf(rewards);
    }

    public static OnlineRewardConfig load(HolderLookup.Provider registries) {
        if (!Files.exists(FILE)) {
            OnlineRewardConfig defaults = defaults();
            write(defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            JsonArray array = root != null && root.isJsonObject()
                    ? root.getAsJsonObject().getAsJsonArray("rewards") : null;
            if (array == null && root != null && root.isJsonObject()) {
                array = root.getAsJsonObject().getAsJsonArray("onlineTimeRewards");
            }
            if (array == null) {
                throw new JsonParseException("rewards must be an array");
            }
            List<Reward> parsed = new ArrayList<>();
            int previous = 0;
            for (int index = 0; index < array.size(); index++) {
                JsonElement element = array.get(index);
                if (!element.isJsonObject()) {
                    throw new JsonParseException("rewards[" + index + "] must be an object");
                }
                JsonObject object = element.getAsJsonObject();
                int minutes = positiveInt(object, "minutes");
                String id = object.has("id") ? object.get("id").getAsString()
                        : "online_" + minutes + "m";
                id = id.trim().toLowerCase(Locale.ROOT);
                final String rewardId = id;
                if (!ID.matcher(rewardId).matches()
                        || !parsed.stream().noneMatch(reward -> reward.id().equals(rewardId))) {
                    throw new JsonParseException("reward id is invalid or duplicated: " + rewardId);
                }
                if (minutes <= previous) {
                    throw new JsonParseException("rewards must be ordered by distinct minutes");
                }
                List<RewardDefinition> definitions = object.has("rewards")
                        ? RewardDefinition.parseArray(object.get("rewards"), "rewards[" + index + "].rewards",
                        registries)
                        : List.of(RewardDefinition.currency("legacy_currency", nonNegativeLong(object, "coins")));
                if (definitions.isEmpty()) {
                    throw new JsonParseException("rewards[" + index + "].rewards must not be empty");
                }
                parsed.add(new Reward(rewardId, minutes, definitions));
                previous = minutes;
            }
            return new OnlineRewardConfig(parsed);
        } catch (IOException | RuntimeException exception) {
            System.err.println("[omnitools] Could not load " + FILE + ": " + exception.getMessage()
                    + ". The configuration snapshot will not be replaced.");
            throw new IllegalStateException("Invalid online reward configuration", exception);
        }
    }

    public static Path path() {
        return FILE;
    }

    public List<Reward> rewards() {
        return rewards;
    }

    public static OnlineRewardConfig empty() {
        return new OnlineRewardConfig(List.of());
    }

    private static OnlineRewardConfig defaults() {
        return new OnlineRewardConfig(List.of(
                new Reward("online_30m", 30, List.of(RewardDefinition.currency("currency", 50L))),
                new Reward("online_60m", 60, List.of(RewardDefinition.currency("currency", 100L))),
                new Reward("online_120m", 120, List.of(RewardDefinition.currency("currency", 250L)))));
    }

    private static void write(OnlineRewardConfig config) {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format_version", 1);
            JsonArray rewards = new JsonArray();
            config.rewards.forEach(reward -> {
                JsonObject object = new JsonObject();
                object.addProperty("id", reward.id());
                object.addProperty("minutes", reward.minutes());
                JsonArray definitions = new JsonArray();
                for (RewardDefinition definition : reward.rewards()) {
                    if (definition.type() != dev.modmind.omnitools.reward.RewardType.CURRENCY) {
                        continue;
                    }
                    JsonObject rewardDefinition = new JsonObject();
                    rewardDefinition.addProperty("id", definition.id());
                    rewardDefinition.addProperty("type", definition.type().serializedName());
                    rewardDefinition.addProperty("amount", definition.amount());
                    definitions.add(rewardDefinition);
                }
                object.add("rewards", definitions);
                rewards.add(object);
            });
            root.add("rewards", rewards);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException exception) {
            System.err.println("[omnitools] Could not create " + FILE + ": " + exception.getMessage());
        }
    }

    private static int positiveInt(JsonObject object, String key) {
        long value = nonNegativeLong(object, key);
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw new JsonParseException(key + " must be positive");
        }
        return (int) value;
    }

    private static long nonNegativeLong(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(key + " must be a non-negative integer");
        }
        long value = Long.parseLong(element.getAsString());
        if (value < 0) {
            throw new JsonParseException(key + " must be non-negative");
        }
        return value;
    }

    public record Reward(String id, int minutes, List<RewardDefinition> rewards) {
        public Reward {
            rewards = List.copyOf(rewards == null ? List.of() : rewards);
        }
    }
}
