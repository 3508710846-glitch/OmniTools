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
    void retainsSessionSnapshotHashesChangedSlotsAndCheckpointReasonAcrossSerialization() {
        List<ItemStack> opened = emptyPage();
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(4, new ItemStack(Items.DIAMOND, 3));
        UUID sessionId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        CloudStorageJournalData journal = new CloudStorageJournalData();

        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 250L, sessionId, "close", opened);
        CloudStorageJournalData.Entry restored = CloudStorageJournalData.fromTag(CloudStorageJournalData.toTag(journal))
                .find(prepared.operationId()).orElseThrow();

        CloudStorageJournalData.SessionMetadata metadata = restored.sessionMetadata();
        assertEquals(sessionId, metadata.sessionId());
        assertEquals(List.of(4), metadata.changedSlots());
        assertEquals("close", metadata.checkpointReason());
        assertEquals(64, metadata.openedPageHash().length());
        assertEquals(64, metadata.targetPageHash().length());
        assertEquals(250L, metadata.checkpointAt());
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
    void quarantinedEvidenceIsAlsoTerminalAndCannotBeReclassified() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(0, new ItemStack(Items.DIAMOND, 1));
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 100L);
        journal.transition(prepared.operationId(), CloudStorageJournalData.Status.QUARANTINED,
                "storage outcome cannot be proven");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> journal.transition(prepared.operationId(), CloudStorageJournalData.Status.COMMITTED,
                        "incorrect recovery reclassification"));
        assertEquals(CloudStorageJournalData.Status.QUARANTINED,
                journal.find(prepared.operationId()).orElseThrow().status());
    }

    @Test
    void retryingTheSameQuarantinedTerminalTransitionKeepsEvidenceUnchanged() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(0, new ItemStack(Items.DIAMOND, 1));
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 100L);
        CloudStorageJournalData.Entry quarantined = journal.transition(prepared.operationId(),
                CloudStorageJournalData.Status.QUARANTINED, "outcome cannot be proven");

        assertEquals(quarantined, journal.transition(prepared.operationId(),
                CloudStorageJournalData.Status.QUARANTINED, "duplicate startup inspection"));
        assertTrue(journal.hasUnresolvedOperation(OWNER, 0));
    }

    @Test
    void resolvedQuarantineKeepsItsTerminalStatusAndDecision() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(0, new ItemStack(Items.DIAMOND, 1));
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 100L);
        CloudStorageJournalData.Entry quarantined = journal.transition(prepared.operationId(),
                CloudStorageJournalData.Status.QUARANTINED, "outcome cannot be proven");
        CloudStorageJournalData.Entry resolved = quarantined.withResolution(CloudStorageJournalData.Resolution.COMMIT,
                "admin", 200L).withResolutionApplied(300L);
        assertEquals(CloudStorageJournalData.Status.QUARANTINED, resolved.status());
        assertEquals(CloudStorageJournalData.Resolution.COMMIT, resolved.resolution());
        assertTrue(resolved.resolutionApplied());
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
    void unresolvedJournalBlocksEveryPageForTheSameOwnerButCommittedEvidenceDoesNot() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(1, new ItemStack(Items.EMERALD, 1));
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 300L);

        assertTrue(journal.hasUnresolvedOperation(OWNER));
        journal.transition(prepared.operationId(), CloudStorageJournalData.Status.COMMITTED, "page persisted");
        assertTrue(!journal.hasUnresolvedOperation(OWNER));
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

    @Test
    void preparedOperationWithOnlyTheBeforeImageIsQuarantinedAndStaysTerminal() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(2, new ItemStack(Items.EMERALD, 4));
        CloudStorageData storage = new CloudStorageData();
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 400L);

        CloudStorageJournalData.RecoveryReport first = journal.reconcile(storage);
        assertEquals(new CloudStorageJournalData.RecoveryReport(0, 1), first);
        CloudStorageJournalData.Entry quarantined = journal.find(prepared.operationId()).orElseThrow();
        assertEquals(CloudStorageJournalData.Status.QUARANTINED, quarantined.status());
        assertTrue(journal.hasUnresolvedOperation(OWNER));

        CloudStorageJournalData.RecoveryReport retry = journal.reconcile(storage);
        assertEquals(new CloudStorageJournalData.RecoveryReport(0, 0), retry);
        assertEquals(quarantined, journal.find(prepared.operationId()).orElseThrow());
    }

    @Test
    void crashAfterPreparedFlushKeepsTheOriginalPageAndQuarantinesAfterRestart() {
        List<ItemStack> before = emptyPage();
        before.set(3, new ItemStack(Items.IRON_INGOT, 8));
        List<ItemStack> after = emptyPage();
        after.set(3, new ItemStack(Items.IRON_INGOT, 12));
        CloudStorageData storage = new CloudStorageData();
        storage.replacePage(OWNER, 0, before);
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 500L);

        CloudStorageJournalData restartedJournal = restart(journal);
        CloudStorageData restartedStorage = restart(storage);
        assertEquals(new CloudStorageJournalData.RecoveryReport(0, 1), restartedJournal.reconcile(restartedStorage));

        assertEquals(8, restartedStorage.page(OWNER, 0).get(3).getCount());
        assertEquals(CloudStorageJournalData.Status.QUARANTINED,
                restartedJournal.find(prepared.operationId()).orElseThrow().status());
        assertTrue(restartedJournal.hasUnresolvedOperation(OWNER));
    }

    @Test
    void crashAfterPageFlushBeforeCommitFlushCommitsThePreparedOperationAfterRestart() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(5, new ItemStack(Items.DIAMOND, 2));
        CloudStorageData storage = new CloudStorageData();
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 600L);
        storage.replacePage(OWNER, 0, after);

        CloudStorageJournalData restartedJournal = restart(journal);
        CloudStorageData restartedStorage = restart(storage);
        assertEquals(new CloudStorageJournalData.RecoveryReport(1, 0), restartedJournal.reconcile(restartedStorage));

        assertEquals(2, restartedStorage.page(OWNER, 0).get(5).getCount());
        assertEquals(CloudStorageJournalData.Status.COMMITTED,
                restartedJournal.find(prepared.operationId()).orElseThrow().status());
        assertTrue(!restartedJournal.hasUnresolvedOperation(OWNER));
    }

    @Test
    void crashAfterCommitFlushLeavesTheCommittedRecordAndPageUntouchedAfterRestart() {
        List<ItemStack> before = emptyPage();
        List<ItemStack> after = emptyPage();
        after.set(8, new ItemStack(Items.EMERALD, 6));
        CloudStorageData storage = new CloudStorageData();
        CloudStorageJournalData journal = new CloudStorageJournalData();
        CloudStorageJournalData.Entry prepared = journal.prepare(OWNER, 0,
                CloudStorageJournalData.Operation.DEPOSIT, before, after, 700L);
        storage.replacePage(OWNER, 0, after);
        journal.transition(prepared.operationId(), CloudStorageJournalData.Status.COMMITTED, "page persisted");

        CloudStorageJournalData restartedJournal = restart(journal);
        CloudStorageData restartedStorage = restart(storage);
        assertEquals(new CloudStorageJournalData.RecoveryReport(0, 0), restartedJournal.reconcile(restartedStorage));

        assertEquals(6, restartedStorage.page(OWNER, 0).get(8).getCount());
        assertEquals(CloudStorageJournalData.Status.COMMITTED,
                restartedJournal.find(prepared.operationId()).orElseThrow().status());
        assertTrue(!restartedJournal.hasUnresolvedOperation(OWNER));
    }

    private static CloudStorageJournalData restart(CloudStorageJournalData data) {
        return CloudStorageJournalData.fromTag(CloudStorageJournalData.toTag(data));
    }

    private static CloudStorageData restart(CloudStorageData data) {
        return CloudStorageData.fromTag(CloudStorageData.toTag(data));
    }

    private static List<ItemStack> emptyPage() {
        List<ItemStack> page = new ArrayList<>(CloudStorageData.SLOTS_PER_PAGE);
        for (int slot = 0; slot < CloudStorageData.SLOTS_PER_PAGE; slot++) {
            page.add(ItemStack.EMPTY);
        }
        return page;
    }
}
