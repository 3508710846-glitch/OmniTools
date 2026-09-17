package dev.modmind.omnitools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudStorageScreenHandlerTest {
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
}
