package dev.modmind.omnitools;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/** Shared server-side presentation primitives for all OmniTools menus. */
public final class GuiTheme {
    private GuiTheme() {
    }

    /** Empty slots are intentionally blank; handlers still reject all decorative clicks. */
    public static ItemStack emptySlot() {
        return ItemStack.EMPTY;
    }

    public static void clear(SimpleContainer container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            container.setItem(slot, ItemStack.EMPTY);
        }
    }

    public static ItemStack named(Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        List<Component> compact = GuiTextService.compactLore(lore);
        if (!compact.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(compact));
        }
        return stack;
    }

    public static ItemStack status(Item item, Component name, ChatFormatting color,
                                   List<Component> lore, boolean glint) {
        ItemStack stack = named(item, name.copy().withStyle(color, ChatFormatting.BOLD), lore);
        if (glint) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    public static ItemStack navigation(Item item, Component name, Component hint) {
        return named(item, name.copy().withStyle(ChatFormatting.AQUA),
                hint == null ? List.of() : List.of(hint.copy().withStyle(ChatFormatting.GRAY)));
    }
}
