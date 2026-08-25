package dev.modmind.omnitools.commandmenu;

import dev.modmind.omnitools.text.TextTemplateRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Map;

/** Parsed contents of one independent menu JSON file. */
public record CommandMenuPageConfig(String id, String titleTemplate, int size, ItemStack filler,
                                    String fillerNameTemplate, List<String> fillerLoreTemplates,
                                    Map<Integer, CommandMenuItem> items) {
    public CommandMenuPageConfig {
        filler = filler.copy();
        fillerLoreTemplates = List.copyOf(fillerLoreTemplates);
        items = Map.copyOf(items);
    }

    public int rows() {
        return size / 9;
    }

    public Component title(ServerPlayer player) {
        return TextTemplateRenderer.render(player, titleTemplate);
    }

    public ItemStack fillerStack(ServerPlayer player) {
        ItemStack display = filler.copy();
        if (fillerNameTemplate != null) {
            display.set(DataComponents.CUSTOM_NAME, TextTemplateRenderer.render(player, fillerNameTemplate));
        }
        if (!fillerLoreTemplates.isEmpty()) {
            display.set(DataComponents.LORE, new ItemLore(fillerLoreTemplates.stream()
                    .map(text -> TextTemplateRenderer.render(player, text)).toList()));
        }
        return display;
    }
}
