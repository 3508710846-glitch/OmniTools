package dev.modmind.omnitools;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TitleEffectConfigTest {
    @Test
    void parsesAllInlineEffectTypesAndFallsBackDisplayToName() {
        assertEquals(TitleEffectConfig.EffectType.POTION,
                TitleEffectConfig.parseInlineDefinition(potion("haste", "minecraft:haste"), "test").type());
        assertEquals(TitleEffectConfig.EffectType.ATTRIBUTE,
                TitleEffectConfig.parseInlineDefinition(attribute("health", "minecraft:generic.max_health"), "test").type());
        assertEquals(TitleEffectConfig.EffectType.PARTICLE,
                TitleEffectConfig.parseInlineDefinition(particle("trail", "minecraft:flame"), "test").type());
        JsonObject permission = new JsonObject();
        permission.addProperty("id", "builder");
        permission.addProperty("name", "Builder");
        permission.addProperty("type", "PERMISSION");
        permission.addProperty("permission", "omnitools:command.admin");
        assertEquals("Builder", TitleEffectConfig.parseInlineDefinition(permission, "test").display());
    }

    @Test
    void rejectsInvalidInlineEffectValues() {
        JsonObject invalidDuration = potion("haste", "minecraft:haste");
        invalidDuration.addProperty("duration", 0);
        assertThrows(RuntimeException.class,
                () -> TitleEffectConfig.parseInlineDefinition(invalidDuration, "test"));

        JsonObject invalidId = potion("bad id", "minecraft:haste");
        assertThrows(RuntimeException.class,
                () -> TitleEffectConfig.parseInlineDefinition(invalidId, "test"));
    }

    @Test
    void embeddedTitleEffectsAreImmutableAndEmptyMeansNoEffects() {
        TitleConfig.TitleDefinition title = new TitleConfig.TitleDefinition("plain", "Plain",
                TitleRarity.COMMON, List.of(), List.of(), List.of(), true);
        assertEquals(List.of(), title.embeddedEffects());
        assertEquals(List.of(), title.effects());
        assertEquals(true, title.inlineEffectsConfigured());
    }

    @Test
    void parsesV2TitlesAndKeepsV1EffectIdsCompatible() {
        JsonObject v2 = new JsonObject();
        v2.addProperty("format_version", 2);
        JsonArray titles = new JsonArray();
        JsonObject title = new JsonObject();
        title.addProperty("id", "haste_title");
        title.addProperty("display", "Haste");
        title.addProperty("rarity", "common");
        JsonArray effects = new JsonArray();
        effects.add(potion("haste", "minecraft:haste"));
        title.add("effects", effects);
        titles.add(title);
        v2.add("titles", titles);
        TitleConfig.TitleDefinition parsed = TitleConfig.parse(v2).definition("haste_title").orElseThrow();
        assertEquals(1, parsed.embeddedEffects().size());
        assertEquals(true, parsed.inlineEffectsConfigured());

        JsonObject v1 = new JsonObject();
        v1.addProperty("format_version", 1);
        JsonArray legacyTitles = new JsonArray();
        JsonObject legacy = new JsonObject();
        legacy.addProperty("id", "legacy");
        legacy.addProperty("display", "Legacy");
        legacy.addProperty("rarity", "common");
        JsonArray ids = new JsonArray();
        ids.add("speed_1");
        legacy.add("effects", ids);
        legacyTitles.add(legacy);
        v1.add("titles", legacyTitles);
        TitleConfig.TitleDefinition legacyParsed = TitleConfig.parse(v1).definition("legacy").orElseThrow();
        assertEquals(List.of("speed_1"), legacyParsed.effects());
        assertEquals(false, legacyParsed.inlineEffectsConfigured());
    }

    private static JsonObject potion(String id, String effect) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("name", id);
        object.addProperty("type", "POTION");
        object.addProperty("effect", effect);
        object.addProperty("amplifier", 0);
        object.addProperty("duration", -1);
        return object;
    }

    private static JsonObject attribute(String id, String attribute) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("name", id);
        object.addProperty("type", "ATTRIBUTE");
        object.addProperty("attribute", attribute);
        object.addProperty("operation", "ADDITION");
        object.addProperty("amount", 1.0D);
        return object;
    }

    private static JsonObject particle(String id, String particle) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("name", id);
        object.addProperty("type", "PARTICLE");
        object.addProperty("particle", particle);
        object.addProperty("frequency", 10);
        return object;
    }
}
