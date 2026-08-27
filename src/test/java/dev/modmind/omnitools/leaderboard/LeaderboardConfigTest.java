package dev.modmind.omnitools.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardConfigTest {
    @BeforeAll
    static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void resolvesGroupsAndKeepsConfiguredStatisticSemantics() {
        JsonObject root = root();
        JsonObject groups = new JsonObject();
        JsonArray stone = new JsonArray();
        stone.add("minecraft:stone");
        stone.add("minecraft:deepslate");
        groups.add("stone_family", stone);
        root.add("target_groups", groups);
        JsonObject board = board("mine_stone", "block_mined");
        JsonArray targets = new JsonArray();
        targets.add("$stone_family");
        board.getAsJsonObject("stat").add("targets", targets);
        root.getAsJsonArray("leaderboards").add(board);

        LeaderboardConfig config = LeaderboardConfig.parse(root);

        assertEquals(2, config.leaderboards().getFirst().stat().targets().size());
        assertEquals("minecraft:stone", config.leaderboards().getFirst().stat().targets().getFirst());
        assertEquals("sum", config.leaderboards().getFirst().stat().aggregation().name().toLowerCase());
    }

    @Test
    void acceptsBlockItemSelectorOnlyForItemUsed() {
        JsonObject accepted = root();
        JsonObject board = board("builder", "item_used");
        JsonArray targets = new JsonArray();
        targets.add("@block_items");
        board.getAsJsonObject("stat").add("targets", targets);
        accepted.getAsJsonArray("leaderboards").add(board);
        assertTrue(LeaderboardConfig.parse(accepted).leaderboards().getFirst().stat().targets().size() > 1);

        JsonObject rejected = root();
        JsonObject wrong = board("not_builder", "item_crafted");
        JsonArray wrongTargets = new JsonArray();
        wrongTargets.add("@block_items");
        wrong.getAsJsonObject("stat").add("targets", wrongTargets);
        rejected.getAsJsonArray("leaderboards").add(wrong);
        assertThrows(JsonParseException.class, () -> LeaderboardConfig.parse(rejected));
    }

    @Test
    void rejectsCustomQueriesThatAlsoContainTargets() {
        JsonObject root = root();
        JsonObject board = board("fall", "custom");
        board.getAsJsonObject("stat").addProperty("custom_stat", "minecraft:fall_one_cm");
        JsonArray targets = new JsonArray();
        targets.add("minecraft:stone");
        board.getAsJsonObject("stat").add("targets", targets);
        root.getAsJsonArray("leaderboards").add(board);
        assertThrows(JsonParseException.class, () -> LeaderboardConfig.parse(root));
    }

    private static JsonObject root() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", 1);
        root.add("leaderboards", new JsonArray());
        return root;
    }

    private static JsonObject board(String id, String type) {
        JsonObject board = new JsonObject();
        board.addProperty("id", id);
        board.addProperty("icon", "minecraft:stone");
        JsonObject stat = new JsonObject();
        stat.addProperty("type", type);
        stat.addProperty("aggregation", "sum");
        stat.addProperty("unit", "count");
        board.add("stat", stat);
        return board;
    }
}
