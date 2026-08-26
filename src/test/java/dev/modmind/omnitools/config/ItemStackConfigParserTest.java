package dev.modmind.omnitools.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import dev.modmind.omnitools.reward.RewardDefinition;
import dev.modmind.omnitools.reward.RewardEvent;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemStackConfigParserTest {
    private static final String NAMED_UNBREAKABLE_SWORD =
            "{id:'minecraft:diamond_sword',count:1,components:{"
                    + "'minecraft:custom_name':{text:'签到宝剑',color:'gold',italic:false},"
                    + "'minecraft:unbreakable':{}}}";
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void parsesSimpleNamedUnbreakableAndPotionStacks() throws Exception {
        ItemStack bread = ItemStackConfigParser.parse(item("minecraft:bread", 8), registries, "simple", 64);
        assertEquals(Items.BREAD, bread.getItem());
        assertEquals(8, bread.getCount());

        ItemStack sword = ItemStackConfigParser.parse(nbt(NAMED_UNBREAKABLE_SWORD), registries,
                "named sword", 64);
        assertEquals(Items.DIAMOND_SWORD, sword.getItem());
        assertEquals(1, sword.getCount());
        assertEquals("签到宝剑", sword.get(DataComponents.CUSTOM_NAME).getString());
        assertDoesNotThrow(() -> ItemStackConfigParser.validateRewardSnapshot(sword, registries, "named sword", 64));

        ItemStack potion = ItemStackConfigParser.parse(
                nbt("{id:'minecraft:potion',count:1,components:{'minecraft:potion_contents':{potion:'minecraft:strong_healing'}}}"),
                registries, "potion", 64);
        assertEquals(Items.POTION, potion.getItem());
    }

    @Test
    void unifiedRewardDefinitionsAcceptSnbtForEveryRewardSource() {
        for (String context : new String[]{"daily.rewards", "online.rewards", "achievements.rewards"}) {
            JsonArray rewards = new JsonArray();
            JsonObject reward = nbt(NAMED_UNBREAKABLE_SWORD);
            reward.addProperty("id", "named_sword");
            reward.addProperty("type", "item");
            rewards.add(reward);

            RewardDefinition parsed = RewardDefinition.parseArray(rewards, context, registries).getFirst();
            assertEquals(Items.DIAMOND_SWORD, parsed.createItemStack().getItem());
            assertEquals(1, parsed.createItemStack().getCount());
        }
    }

    @Test
    void rejectsAmbiguousInvalidAndOversizedItemDefinitions() {
        JsonObject mixed = nbt(NAMED_UNBREAKABLE_SWORD);
        mixed.addProperty("item", "minecraft:diamond_sword");
        assertThrows(JsonParseException.class, () -> ItemStackConfigParser.parse(mixed, registries, "mixed", 64));
        assertThrows(JsonParseException.class, () -> ItemStackConfigParser.parse(nbt("{broken"), registries,
                "bad snbt", 64));
        assertThrows(JsonParseException.class, () -> ItemStackConfigParser.parse(
                nbt("{id:'minecraft:unknown_item',count:1}"), registries, "unknown item", 64));
        assertThrows(JsonParseException.class, () -> ItemStackConfigParser.parse(
                nbt("{id:'minecraft:stone',count:1,components:{'minecraft:unknown_component':{}}}"), registries,
                "unknown component", 64));
        assertThrows(JsonParseException.class, () -> ItemStackConfigParser.parse(
                nbt("{id:'minecraft:air',count:1}"), registries, "air", 64));
        assertThrows(JsonParseException.class, () -> ItemStackConfigParser.parse(
                nbt("{id:'minecraft:stone',count:65}"), registries, "too many", 64));
        assertThrows(JsonParseException.class, () -> ItemStackConfigParser.parse(item("minecraft:stone", 0),
                registries, "zero", 64));
        assertThrows(JsonParseException.class, () -> ItemStackConfigParser.parse(
                nbt("x".repeat(ItemStackConfigParser.MAX_SNBT_BYTES + 1)), registries, "oversized", 64));
    }

    @Test
    void ledgerSnapshotsPreserveItemComponentsInsteadOfFollowingLaterConfiguration() {
        ItemStack source = new ItemStack(Items.DIAMOND_SWORD);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("旧奖励快照"));
        RewardClaimLedger ledger = new RewardClaimLedger();
        RewardEvent event = RewardEvent.online(UUID.fromString("11111111-2222-3333-4444-555555555555"),
                20691L, "online_30m");

        ItemStack queued = ledger.queueItem(event, "named_sword", source, registries);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("新配置名称"));
        ItemStack restored = ledger.queuedItem(event, "named_sword", registries);

        assertTrue(ItemStack.isSameItemSameComponents(queued, restored));
        assertEquals(queued.getCount(), restored.getCount());
        assertEquals("旧奖励快照", restored.get(DataComponents.CUSTOM_NAME).getString());
        assertFalse(ItemStack.isSameItemSameComponents(source, restored));
    }

    private static JsonObject item(String item, int count) {
        JsonObject object = new JsonObject();
        object.addProperty("item", item);
        object.addProperty("count", count);
        return object;
    }

    private static JsonObject nbt(String value) {
        JsonObject object = new JsonObject();
        object.addProperty("nbt", value);
        return object;
    }
}
