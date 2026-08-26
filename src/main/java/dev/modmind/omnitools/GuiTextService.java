package dev.modmind.omnitools;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/** Keeps ordinary player-facing names and Lore short enough for vanilla screens. */
public final class GuiTextService {
    private static final int MAX_LORE_LINES = ItemLore.MAX_LINES;
    private static final int MAX_VISIBLE_LINE_LENGTH = 80;
    private static final int MAX_CARD_LORE_LINES = 6;

    private GuiTextService() {
    }

    public static List<Component> compactLore(List<Component> lines) {
        return compactLore(lines, MAX_LORE_LINES);
    }

    public static List<Component> compactLore(List<Component> lines, int maximumLines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(0, Math.min(MAX_LORE_LINES, maximumLines));
        if (limit == 0) {
            return List.of();
        }
        List<Component> result = new ArrayList<>(Math.min(lines.size(), limit));
        for (Component line : lines) {
            if (line == null || result.size() == limit) {
                continue;
            }
            String visible = line.getString();
            result.add(visible.length() <= MAX_VISIBLE_LINE_LENGTH ? line
                    : Component.literal(visible.substring(0, MAX_VISIBLE_LINE_LENGTH - 3) + "..."));
        }
        return List.copyOf(result);
    }

    /** Keeps a status/action footer visible after truncating a compact player-facing card. */
    public static List<Component> cardLore(List<Component> details, Component footer) {
        List<Component> lines = new ArrayList<>(MAX_CARD_LORE_LINES);
        int detailLimit = Math.max(0, Math.min(MAX_CARD_LORE_LINES, MAX_LORE_LINES) - 1);
        if (details != null) {
            for (Component detail : details) {
                if (detail != null && lines.size() == detailLimit) {
                    break;
                }
                if (detail != null) {
                    lines.add(detail);
                }
            }
        }
        if (footer != null) {
            lines.add(footer);
        }
        return compactLore(lines, MAX_CARD_LORE_LINES);
    }

    public static Component page(int current, int total, int count) {
        return ServerText.translatable("gui.omnitools.gui.page", current, total, count);
    }
}
