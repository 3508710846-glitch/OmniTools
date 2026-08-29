package dev.modmind.omnitools;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiTextServiceTest {
    @Test
    void defaultCardsRemainSixLinesAndRetainFooter() {
        List<Component> details = List.of(
                Component.literal("one"), Component.literal("two"), Component.literal("three"),
                Component.literal("four"), Component.literal("five"), Component.literal("six"));

        List<Component> lore = GuiTextService.cardLore(details, Component.literal("status"));

        assertEquals(6, lore.size());
        assertEquals("status", lore.get(5).getString());
    }

    @Test
    void achievementCardsCanUseTenLinesWithoutDroppingFooter() {
        List<Component> details = List.of(
                Component.literal("one"), Component.literal("two"), Component.literal("three"),
                Component.literal("four"), Component.literal("five"), Component.literal("six"),
                Component.literal("seven"), Component.literal("eight"), Component.literal("nine"),
                Component.literal("ten"));

        List<Component> lore = GuiTextService.cardLore(details, Component.literal("claimable"), 10);

        assertEquals(10, lore.size());
        assertEquals("claimable", lore.get(9).getString());
        assertTrue(lore.stream().anyMatch(line -> line.getString().equals("nine")));
    }
}
