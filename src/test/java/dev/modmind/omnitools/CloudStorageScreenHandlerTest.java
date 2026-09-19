package dev.modmind.omnitools;

import net.minecraft.world.item.ItemStack;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudStorageScreenHandlerTest {
    @BeforeAll
    static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void quickMoveOnlyAcceptsCloudAndPlayerInventorySlots() {
        assertTrue(CloudStorageScreenHandler.isQuickMoveSource(0));
        assertTrue(CloudStorageScreenHandler.isQuickMoveSource(CloudStorageScreenHandler.STORAGE_SLOT_COUNT - 1));
        assertTrue(CloudStorageScreenHandler.isQuickMoveSource(CloudStorageScreenHandler.CONTAINER_SIZE));
        assertTrue(CloudStorageScreenHandler.isQuickMoveSource(CloudStorageScreenHandler.CONTAINER_SIZE + 35));
    }

    @Test
    void quickMoveRejectsEveryReadOnlyControlSlot() {
        for (int slot = CloudStorageScreenHandler.STORAGE_SLOT_COUNT;
             slot < CloudStorageScreenHandler.CONTAINER_SIZE; slot++) {
            assertFalse(CloudStorageScreenHandler.isQuickMoveSource(slot), "control slot " + slot);
        }
        assertFalse(CloudStorageScreenHandler.isQuickMoveSource(-1));
        assertFalse(CloudStorageScreenHandler.isQuickMoveSource(CloudStorageScreenHandler.CONTAINER_SIZE + 36));
    }

    @Test
    void checkpointSchedulingUsesTheWorldTimeEpochInsteadOfRestartedServerTicks() {
        assertEquals(4_000_020L, CloudStorageSessionManager.checkpointClock(4_000_020L, 20L));
        assertEquals(20L, CloudStorageSessionManager.checkpointClock(-1L, 20L));
    }

    @Test
    void firstAutomaticCheckpointDoesNotOverflowTheNeverCheckpointedSentinel() {
        CloudStorageSession session = new CloudStorageSession(UUID.randomUUID(), UUID.randomUUID(), 0,
                emptyPage(), 4_000_000L);
        session.markChanged(4_000_001L);

        assertFalse(CloudStorageCommitService.due(session, 4_000_020L));
        assertTrue(CloudStorageCommitService.due(session, 4_000_021L));
        assertTrue(CloudStorageCommitService.elapsedAtLeast(4_000_021L, Long.MIN_VALUE,
                CloudStorageCommitService.CHECKPOINT_DELAY_TICKS));
    }

    @Test
    void laterAutomaticCheckpointWaitsForBothTheChangeAndPreviousCommit() {
        CloudStorageSession session = new CloudStorageSession(UUID.randomUUID(), UUID.randomUUID(), 0,
                emptyPage(), 4_000_000L);
        session.markChanged(4_000_001L);
        session.beginCommit();
        session.completeCheckpoint(4_000_021L, emptyPage());
        session.markChanged(4_000_025L);

        assertFalse(CloudStorageCommitService.due(session, 4_000_040L));
        assertTrue(CloudStorageCommitService.due(session, 4_000_045L));
        assertFalse(CloudStorageCommitService.elapsedAtLeast(5L, 10L,
                CloudStorageCommitService.CHECKPOINT_DELAY_TICKS));
    }

    @Test
    void delayedMenuTeardownDoesNotRecommitReleasedOrClosedSessions() {
        CloudStorageSession session = new CloudStorageSession(UUID.randomUUID(), UUID.randomUUID(), 0, emptyPage(), 0L);

        // A faulted module invalidates the menu for further interaction, but the mirror remains
        // authoritative until its normal close transaction runs.
        assertTrue(CloudStorageSessionManager.shouldCommitOnMenuClose(session, true));
        assertFalse(CloudStorageSessionManager.shouldCommitOnMenuClose(session, false));

        session.close(); // Models the successful disconnect/stop commit before menu removed().
        assertFalse(CloudStorageSessionManager.shouldCommitOnMenuClose(session, true));
        assertFalse(CloudStorageSessionManager.shouldCommitOnMenuClose(session, false));
    }

    private static List<ItemStack> emptyPage() {
        List<ItemStack> page = new ArrayList<>(CloudStorageData.SLOTS_PER_PAGE);
        for (int slot = 0; slot < CloudStorageData.SLOTS_PER_PAGE; slot++) page.add(ItemStack.EMPTY);
        return page;
    }
}
