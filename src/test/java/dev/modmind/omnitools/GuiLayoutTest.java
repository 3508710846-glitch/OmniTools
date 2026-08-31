package dev.modmind.omnitools;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiLayoutTest {
    @Test
    void sharedLayoutsKeepHeaderContentAndNavigationSeparate() {
        assertEquals(36, GuiSlots.CONTENT_SLOT_COUNT_54);
        assertEquals(9, GuiSlots.CONTENT_SLOT_COUNT_27);
        assertEquals(9, GuiSlots.contentSlot54(0));
        assertEquals(44, GuiSlots.contentSlot54(35));
        assertEquals(9, GuiSlots.contentSlot27(0));
        assertEquals(17, GuiSlots.contentSlot27(8));
        assertFalse(GuiSlots.isContentSlot54(GuiSlots.HEADER_CLOSE_54));
        assertFalse(GuiSlots.isContentSlot54(GuiSlots.FIRST_ACTION_SLOT_54));
        assertFalse(GuiSlots.isContentSlot27(GuiSlots.HEADER_CLOSE_27));
        assertFalse(GuiSlots.isContentSlot27(GuiSlots.FIRST_ACTION_SLOT_27));
    }

    @Test
    void cardLoreReservesItsLastLineForStatusOrAction() {
        List<Component> lore = GuiTextService.cardLore(List.of(
                Component.literal("description"),
                Component.literal("progress"),
                Component.literal("reward one"),
                Component.literal("reward two"),
                Component.literal("extra detail"),
                Component.literal("overflow")), Component.literal("action"));

        assertEquals(6, lore.size());
        assertEquals("action", lore.getLast().getString());
        assertTrue(lore.stream().noneMatch(line -> line.getString().equals("overflow")));
    }

    @Test
    void cardLoreReservesEveryFixedFooterLine() {
        List<Component> lore = GuiTextService.cardLore(List.of(
                Component.literal("description"),
                Component.literal("condition"),
                Component.literal("reward one"),
                Component.literal("reward two"),
                Component.literal("overflow")),
                List.of(Component.literal("progress"), Component.literal("status")), 6);

        assertEquals(6, lore.size());
        assertEquals("progress", lore.get(4).getString());
        assertEquals("status", lore.getLast().getString());
        assertTrue(lore.stream().noneMatch(line -> line.getString().equals("overflow")));
    }

    @Test
    void pagedMenusUseTheSharedContentAreaWithoutTouchingHeaders() {
        assertEquals(GuiSlots.CONTENT_SLOT_COUNT_54, ShopScreenHandler.PRODUCT_SLOT_COUNT);
        assertEquals(GuiSlots.CONTENT_SLOT_COUNT_54, CheckinRecordsScreenHandler.RECORD_SLOT_COUNT);
        assertEquals(GuiSlots.CONTENT_SLOT_COUNT_54, CheckinRewardInfoScreenHandler.CONTENT_SLOTS);
        assertEquals(GuiSlots.HEADER_CLOSE_54, ShopScreenHandler.CLOSE_SLOT);
        assertNotEquals(GuiSlots.HEADER_CLOSE_54, CheckinRecordsScreenHandler.BACK_SLOT);
        assertNotEquals(GuiSlots.HEADER_CLOSE_54, CheckinRewardInfoScreenHandler.BACK_SLOT);
    }

    @Test
    void cloudStorageControlsDoNotOccupyStorageSlots() {
        assertEquals(CloudStorageData.SLOTS_PER_PAGE, CloudStorageScreenHandler.STORAGE_SLOT_COUNT);
        assertTrue(CloudStorageScreenHandler.PREVIOUS_PAGE_SLOT >= CloudStorageScreenHandler.STORAGE_SLOT_COUNT);
        assertTrue(CloudStorageScreenHandler.CLOSE_SLOT >= CloudStorageScreenHandler.STORAGE_SLOT_COUNT);
        assertEquals(GuiSlots.LAST_SLOT_54, CloudStorageScreenHandler.CLOSE_SLOT);
    }
}
