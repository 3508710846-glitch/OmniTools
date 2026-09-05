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

    @Test
    void terminalStatesCannotBeReclassifiedDuringRecovery() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(0, new ItemStack(Items.DIAMOND, 1));
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 100L);
        journal.transition(prepared.operationId(), CloudStorageJournalData.Status.COMMITTED, "page persisted");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> journal.transition(prepared.operationId(), CloudStorageJournalData.Status.QUARANTINED,
                        "startup inspection failed"));
        assertEquals(CloudStorageJournalData.Status.COMMITTED,
                journal.find(prepared.operationId()).orElseThrow().status());
    }

    @Test
    void repeatedTerminalTransitionPreservesOriginalRecoveryEvidence() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(0, new ItemStack(Items.DIAMOND, 1));
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 100L);
        CloudStorageJournalData.Entry committed = journal.transition(prepared.operationId(),
                CloudStorageJournalData.Status.COMMITTED, "page persisted");

        CloudStorageJournalData.Entry retried = journal.transition(prepared.operationId(),
                CloudStorageJournalData.Status.COMMITTED, "duplicate completion");

        assertEquals(committed, retried);
        assertEquals("page persisted", retried.reason());
    }

    @Test
    void normalCommitRemainsCommittedAfterJournalAndPageRestart() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(4, new ItemStack(Items.DIAMOND, 3));
        CloudStorageData storage = new CloudStorageData();
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 200L);
        storage.replacePage(OWNER, 0, after);
        journal.transition(prepared.operationId(), CloudStorageJournalData.Status.COMMITTED, "page persisted");

        CloudStorageJournalData restoredJournal = CloudStorageJournalData.fromTag(
                CloudStorageJournalData.toTag(journal));
        CloudStorageData restoredStorage = CloudStorageData.fromTag(CloudStorageData.toTag(storage));
        CloudStorageJournalData.RecoveryReport report = restoredJournal.reconcile(restoredStorage);

        assertEquals(new CloudStorageJournalData.RecoveryReport(0, 0), report);
        CloudStorageJournalData.Entry recovered = restoredJournal.find(prepared.operationId()).orElseThrow();
        assertEquals(CloudStorageJournalData.Status.COMMITTED, recovered.status());
        assertEquals(3, restoredStorage.page(OWNER, 0).get(4).getCount());
    }

    @Test
    void inFlightCommitIsCommittedAfterCrashAndRestart() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(7, new ItemStack(Items.EMERALD, 2));
        CloudStorageData storage = new CloudStorageData();
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 300L);
        storage.replacePage(OWNER, 0, after);

        CloudStorageJournalData restoredJournal = CloudStorageJournalData.fromTag(
                CloudStorageJournalData.toTag(journal));
        CloudStorageData restoredStorage = CloudStorageData.fromTag(CloudStorageData.toTag(storage));
        CloudStorageJournalData.RecoveryReport report = restoredJournal.reconcile(restoredStorage);

        assertEquals(new CloudStorageJournalData.RecoveryReport(1, 0), report);
        CloudStorageJournalData.Entry recovered = restoredJournal.find(prepared.operationId()).orElseThrow();
        assertEquals(CloudStorageJournalData.Status.COMMITTED, recovered.status());
        assertEquals(2, restoredStorage.page(OWNER, 0).get(7).getCount());
    }

    private static List<ItemStack> emptyPage() {
        List<ItemStack> page = new ArrayList<>(CloudStorageData.SLOTS_PER_PAGE);
        for (int slot = 0; slot < CloudStorageData.SLOTS_PER_PAGE; slot++) {
            page.add(ItemStack.EMPTY);
        }
        return page;
    }
}
