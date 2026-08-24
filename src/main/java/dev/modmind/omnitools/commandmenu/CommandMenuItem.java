package dev.modmind.omnitools.commandmenu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Immutable display item and its left/right click actions. */
public record CommandMenuItem(int slot, ItemStack stack, List<CommandMenuAction> leftClick,
                              List<CommandMenuAction> rightClick) {
    public CommandMenuItem {
        stack = stack.copy();
        leftClick = List.copyOf(leftClick);
        rightClick = List.copyOf(rightClick);
    }

    public ItemStack displayStack() {
        return stack.copy();
    }

    public static ItemStack withText(ItemStack stack, Component name, List<Component> lore, boolean glow) {
        ItemStack display = stack.copy();
        if (name != null) {
            display.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);
        }
        if (!lore.isEmpty()) {
            display.set(net.minecraft.core.component.DataComponents.LORE,
                    new net.minecraft.world.item.component.ItemLore(lore));
        }
        if (glow) {
            display.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return display;
    }
}
