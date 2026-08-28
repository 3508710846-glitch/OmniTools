package dev.modmind.omnitools.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.reward.RewardDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RewardCatalogTest {
    @Test
    void expandsSharedRewardsAndNestedSetsIntoStableDefinitions() {
        RewardCatalog catalog = RewardCatalog.parse(catalog(
                reward("coins_100", "currency", 100),
                reward("starter_package", "package", "starter"),
                set("daily_basic", "coins_100"),
                set("mine_stone_1000", referenceSet("daily_basic"), "starter_package")), null);
        CommonConfig common = new CommonConfig(Map.of(), Map.of(), Map.of(), catalog);
        JsonArray event = new JsonArray();
        event.add(referenceSet("mine_stone_1000"));

        List<RewardDefinition> definitions = RewardDefinition.parseArray(event, "achievement.rewards", null, common);

        assertEquals(List.of("coins_100", "starter_package"), definitions.stream()
                .map(RewardDefinition::id).toList());
    }

    @Test
    void rejectsDuplicateIdsAfterCombiningRewardSets() {
        RewardCatalog catalog = RewardCatalog.parse(catalog(
                reward("coins_100", "currency", 100),
                set("daily", "coins_100")), null);
        CommonConfig common = new CommonConfig(Map.of(), Map.of(), Map.of(), catalog);
        JsonArray event = new JsonArray();
        event.add(referenceSet("daily"));
        event.add(referenceReward("coins_100"));

        assertThrows(JsonParseException.class,
                () -> RewardDefinition.parseArray(event, "daily.rewards", null, common));
    }

    @Test
    void rejectsUnknownAndCyclicSetReferencesBeforePublishingTheCatalog() {
        assertThrows(JsonParseException.class, () -> RewardCatalog.parse(catalog(
                reward("coins_100", "currency", 100), set("daily", "missing")), null));
        assertThrows(JsonParseException.class, () -> RewardCatalog.parse(catalog(
                reward("coins_100", "currency", 100), set("first", referenceSet("second")),
                set("second", referenceSet("first"))), null));
    }

    @Test
    void rejectsSetNestingBeyondTheConfiguredLimit() {
        JsonObject root = catalog(reward("coins_100", "currency", 100));
        JsonObject sets = root.getAsJsonObject("sets");
        for (int index = 0; index <= RewardCatalog.MAX_SET_DEPTH; index++) {
            String id = "set_" + index;
            sets.add(id, setDefinition(index == RewardCatalog.MAX_SET_DEPTH
                    ? "coins_100" : referenceSet("set_" + (index + 1))));
        }

        assertThrows(JsonParseException.class, () -> RewardCatalog.parse(root, null));
    }

    @Test
    void catalogReferencesCannotOverrideDefinitionsAndLegacyTemplatesStillExpand() {
        RewardCatalog catalog = RewardCatalog.parse(catalog(reward("coins_100", "currency", 100)), null);
        CommonConfig common = new CommonConfig(Map.of("legacy_coins", rewardDefinition("currency", 100)),
                Map.of(), Map.of(), catalog);

        JsonArray legacyAndInline = new JsonArray();
        JsonObject legacyReference = new JsonObject();
        legacyReference.addProperty("$ref", "legacy_coins");
        legacyReference.addProperty("id", "legacy_daily_coins");
        legacyAndInline.add(legacyReference);
        JsonObject inline = rewardDefinition("currency", 25);
        inline.addProperty("id", "inline_coins");
        legacyAndInline.add(inline);

        List<RewardDefinition> definitions = RewardDefinition.parseArray(legacyAndInline, "daily.rewards", null, common);
        assertEquals(List.of("legacy_daily_coins", "inline_coins"), definitions.stream()
                .map(RewardDefinition::id).toList());

        JsonArray overridingReference = new JsonArray();
        JsonObject reference = referenceReward("coins_100");
        reference.addProperty("amount", 999);
        overridingReference.add(reference);
        assertThrows(JsonParseException.class,
                () -> RewardDefinition.parseArray(overridingReference, "daily.rewards", null, common));
    }

    @Test
    void catalogDefinitionsCanReuseLegacyTemplatesWithoutOverridingTheirStableKey() {
        JsonObject root = catalog();
        JsonObject catalogEntry = new JsonObject();
        catalogEntry.addProperty("template", "legacy_currency");
        catalogEntry.addProperty("amount", 100);
        root.getAsJsonObject("rewards").add("coins_100", catalogEntry);

        RewardCatalog catalog = RewardCatalog.parse(root, null,
                Map.of("legacy_currency", rewardDefinition("currency", 50)));

        RewardDefinition definition = catalog.rewards().get("coins_100");
        assertEquals("coins_100", definition.id());
        assertEquals(100L, definition.amount());
    }

    private static JsonObject catalog(Object... entries) {
        JsonObject root = new JsonObject();
        JsonObject rewards = new JsonObject();
        JsonObject sets = new JsonObject();
        for (Object entry : entries) {
            CatalogEntry value = (CatalogEntry) entry;
            if (value.set()) {
                sets.add(value.id(), value.value());
            } else {
                rewards.add(value.id(), value.value());
            }
        }
        root.add("rewards", rewards);
        root.add("sets", sets);
        return root;
    }

    private static CatalogEntry reward(String id, String type, Object value) {
        JsonObject definition = rewardDefinition(type, value);
        return new CatalogEntry(id, false, definition);
    }

    private static JsonObject rewardDefinition(String type, Object value) {
        JsonObject definition = new JsonObject();
        definition.addProperty("type", type);
        if (value instanceof Number number) {
            definition.addProperty("amount", number);
        } else {
            definition.addProperty("package", value.toString());
        }
        return definition;
    }

    private static CatalogEntry set(String id, Object... entries) {
        return new CatalogEntry(id, true, setDefinition(entries));
    }

    private static JsonObject setDefinition(Object... entries) {
        JsonObject definition = new JsonObject();
        JsonArray rewards = new JsonArray();
        for (Object entry : entries) {
            if (entry instanceof JsonObject object) {
                rewards.add(object);
            } else {
                rewards.add(entry.toString());
            }
        }
        definition.add("rewards", rewards);
        return definition;
    }

    private static JsonObject referenceSet(String id) {
        JsonObject reference = new JsonObject();
        reference.addProperty("set", id);
        return reference;
    }

    private static JsonObject referenceReward(String id) {
        JsonObject reference = new JsonObject();
        reference.addProperty("reward", id);
        return reference;
    }

    private record CatalogEntry(String id, boolean set, JsonObject value) {
    }
}
