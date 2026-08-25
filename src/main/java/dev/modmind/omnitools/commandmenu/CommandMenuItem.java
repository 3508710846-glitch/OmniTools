package dev.modmind.omnitools.commandmenu;

import dev.modmind.omnitools.text.TextTemplateRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/** Immutable display item and its left/right click actions. */
public record CommandMenuItem(int slot, ItemStack stack, String nameTemplate, List<String> loreTemplates,
                              boolean glow, List<CommandMenuAction> leftClick, List<CommandMenuAction> rightClick) {
    public CommandMenuItem {
        stack = stack.copy();
        loreTemplates = List.copyOf(loreTemplates);
        leftClick = List.copyOf(leftClick);
        rightClick = List.copyOf(rightClick);
    }

    public ItemStack displayStack(ServerPlayer player) {
        ItemStack display = TextTemplateRenderer.renderItemText(player, stack);
        if (nameTemplate != null) {
            display.set(DataComponents.CUSTOM_NAME, TextTemplateRenderer.render(player, nameTemplate));
        }
        if (!loreTemplates.isEmpty()) {
            display.set(DataComponents.LORE, new ItemLore(loreTemplates.stream()
                    .map(text -> TextTemplateRenderer.render(player, text)).toList()));
        }
        if (glow) {
            display.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return display;
    }
}
