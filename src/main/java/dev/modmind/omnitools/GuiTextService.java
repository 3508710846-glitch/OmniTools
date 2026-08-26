package dev.modmind.omnitools;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/** Keeps ordinary player-facing names and Lore short enough for vanilla screens. */
public final class GuiTextService {
    private static final int MAX_LORE_LINES = ItemLore.MAX_LINES;
    private static final int MAX_VISIBLE_LINE_LENGTH = 80;

    private GuiTextService() {
    }

    public static List<Component> compactLore(List<Component> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<Component> result = new ArrayList<>(Math.min(lines.size(), MAX_LORE_LINES));
        for (Component line : lines) {
            if (line == null || result.size() == MAX_LORE_LINES) {
                continue;
            }
            String visible = line.getString();
            result.add(visible.length() <= MAX_VISIBLE_LINE_LENGTH ? line
                    : Component.literal(visible.substring(0, MAX_VISIBLE_LINE_LENGTH - 3) + "..."));
        }
        return List.copyOf(result);
    }

    public static Component page(int current, int total, int count) {
        return ServerText.translatable("gui.omnitools.gui.page", current, total, count);
    }
}
