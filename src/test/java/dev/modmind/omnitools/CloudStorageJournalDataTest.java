package dev.modmind.omnitools;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudStorageJournalDataTest {
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeAll
    static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void retainsBothPageImagesAndTheCommittedStateAcrossSerialization() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(4, new ItemStack(Items.DIAMOND, 3));
        CloudStorageJournalData journal = new CloudStorageJournalData();

        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.operationFor(before, after), before, after, 100L);
        journal.transition(prepared.operationId(), CloudStorageJournalData.Status.COMMITTED, "page persisted");
        CompoundTag tag = CloudStorageJournalData.toTag(journal);
        CloudStorageJournalData restored = CloudStorageJournalData.fromTag(tag);
        CloudStorageJournalData.Entry recovered = restored.find(prepared.operationId()).orElseThrow();

        assertEquals(CloudStorageJournalData.Status.COMMITTED, recovered.status());
        assertEquals(CloudStorageJournalData.Operation.DEPOSIT, recovered.operation());
        assertTrue(recovered.before().get(4).isEmpty());
        assertEquals(3, recovered.after().get(4).getCount());
    }

    @Test
    void identifiesWithdrawalAndInPageMovesForAudit() {
        List<ItemStack> before = emptyPage();
        before.set(0, new ItemStack(Items.EMERALD, 2));
        List<ItemStack> withdrawn = emptyPage();
        List<ItemStack> moved = emptyPage();
        moved.set(8, new ItemStack(Items.EMERALD, 2));

        assertEquals(CloudStorageJournalData.Operation.WITHDRAW,
                CloudStorageJournalData.operationFor(before, withdrawn));
        assertEquals(CloudStorageJournalData.Operation.MOVE,
                CloudStorageJournalData.operationFor(before, moved));
    }

    private static List<ItemStack> emptyPage() {
        List<ItemStack> page = new ArrayList<>(CloudStorageData.SLOTS_PER_PAGE);
        for (int slot = 0; slot < CloudStorageData.SLOTS_PER_PAGE; slot++) {
            page.add(ItemStack.EMPTY);
        }
        return page;
    }
}
