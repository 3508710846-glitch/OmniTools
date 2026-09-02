package dev.modmind.omnitools;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudStorageDataTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeAll
    static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void preservesACompleteComponentSnapshotAcrossSavedDataRoundTrip() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("云端快照"));
        List<ItemStack> page = pageWith(sword);
        CloudStorageData data = new CloudStorageData();

        data.savePage(OWNER, 0, page);
        CloudStorageData restored = CloudStorageData.fromTag(CloudStorageData.toTag(data));
        ItemStack recovered = restored.page(OWNER, 0).getFirst();

        assertEquals(1, recovered.getCount());
        assertTrue(ItemStack.isSameItemSameComponents(sword, recovered));
        assertEquals("云端快照", recovered.get(DataComponents.CUSTOM_NAME).getString());
    }

    @Test
    void quarantinesMalformedSavedRecordsInsteadOfWritingThemAsEmptyStorage() {
        CompoundTag root = new CompoundTag();
        CompoundTag players = new CompoundTag();
        CompoundTag record = new CompoundTag();
        record.putInt("unlocked_pages", 1);
        CompoundTag pages = new CompoundTag();
        CompoundTag firstPage = new CompoundTag();
        CompoundTag invalidItem = new CompoundTag();
        invalidItem.putString("id", "minecraft:not_a_real_item");
        invalidItem.putInt("count", 1);
        firstPage.put("0", invalidItem);
        pages.put("0", firstPage);
        record.put("pages", pages);
        players.put(OWNER.toString(), record);
        root.put("players", players);

        CloudStorageData data = CloudStorageData.fromTag(root);

        assertTrue(data.isQuarantined(OWNER));
        assertThrows(IllegalStateException.class, () -> data.savePage(OWNER, 0, emptyPage()));
        assertEquals(record, CloudStorageData.toTag(data).getCompoundOrEmpty("players")
                .getCompoundOrEmpty(OWNER.toString()));
    }

    private static List<ItemStack> pageWith(ItemStack first) {
        List<ItemStack> page = emptyPage();
        page.set(0, first);
        return page;
    }

    private static List<ItemStack> emptyPage() {
        List<ItemStack> page = new ArrayList<>(CloudStorageData.SLOTS_PER_PAGE);
        for (int slot = 0; slot < CloudStorageData.SLOTS_PER_PAGE; slot++) {
            page.add(ItemStack.EMPTY);
        }
        return page;
    }
}
