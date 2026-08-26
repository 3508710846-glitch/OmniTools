package dev.modmind.omnitools.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommonConfigTest {
    @Test
    void expandsTemplateAndAllowsEntryOverrides() {
        JsonObject template = new JsonObject();
        template.addProperty("type", "currency");
        template.addProperty("amount", 100);
        CommonConfig common = new CommonConfig(Map.of("coins", template), Map.of(), Map.of());

        JsonObject entry = new JsonObject();
        entry.addProperty("template", "coins");
        entry.addProperty("id", "welcome_coins");
        entry.addProperty("amount", 250);
        JsonArray array = new JsonArray();
        array.add(entry);

        JsonObject expanded = common.expandRewards(array, "rewards").getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("currency", expanded.get("type").getAsString());
        assertEquals("welcome_coins", expanded.get("id").getAsString());
        assertEquals(250, expanded.get("amount").getAsInt());
    }

    @Test
    void rejectsUnknownAndCyclicReferences() {
        JsonObject unknown = new JsonObject();
        unknown.addProperty("template", "missing");
        assertThrows(RuntimeException.class, () -> CommonConfig.empty().expandRewards(unknown, "rewards"));

        JsonObject first = new JsonObject();
        first.addProperty("template", "second");
        JsonObject second = new JsonObject();
        second.addProperty("template", "first");
        CommonConfig cyclic = new CommonConfig(Map.of("first", first, "second", second), Map.of(), Map.of());
        assertThrows(RuntimeException.class, () -> cyclic.expandRewards(first, "rewards"));
    }
}
