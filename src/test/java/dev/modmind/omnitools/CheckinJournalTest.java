package dev.modmind.omnitools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckinJournalTest {
    @Test
    void usesMondayFirstCalendarAndKeepsRightRailOutOfDateSlots() {
        LocalDate august = LocalDate.of(2026, 8, 26);

        assertEquals(5, CheckinTheme.monthStartOffset(august));
        assertEquals(45, CheckinTheme.slotForDay(august, 31));
        assertEquals(31, CheckinTheme.slotToDay(august, 45));
        assertEquals(1, CheckinTheme.slotToDay(august, 5));
        assertTrue(CheckinTheme.slotToDay(august, CheckinTheme.PROFILE_SLOT) == null);
        assertTrue(CheckinTheme.slotToDay(august, CheckinTheme.REFRESH_SLOT) == null);
    }

    @Test
    void uiConfigDefaultsWithoutChangingLegacyRewardFiles() {
        CheckinUiConfig defaults = CheckinUiConfig.parse(new JsonObject());

        assertEquals(CheckinUiConfig.Style.JOURNAL, defaults.style());
        assertTrue(defaults.showWeekday());
        assertTrue(defaults.showProgressBar());
        assertTrue(defaults.showActionHints());
        assertTrue(defaults.showRewardPreview());
        assertTrue(defaults.sounds().open());
    }

    @Test
    void uiConfigValidatesStyleAndVanillaIconBoundary() {
        JsonObject root = new JsonObject();
        JsonObject ui = new JsonObject();
        ui.addProperty("style", "unknown");
        root.add("ui", ui);
        assertThrows(RuntimeException.class, () -> CheckinUiConfig.parse(root));

        JsonObject invalidIconRoot = new JsonObject();
        JsonObject invalidUi = new JsonObject();
        JsonObject icons = new JsonObject();
        icons.addProperty("available", "minecraft:air");
        invalidUi.add("icons", icons);
        invalidIconRoot.add("ui", invalidUi);
        assertThrows(RuntimeException.class, () -> CheckinUiConfig.parse(invalidIconRoot));
    }
}
