package dev.modmind.omnitools;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/** Applies stable status semantics to a module's real icon without replacing that icon. */
public final class GuiStatusItem {
    private GuiStatusItem() {
    }

    public static ItemStack create(ItemStack icon, Component name, State state, List<Component> lore) {
        if (icon == null || icon.isEmpty()) {
            throw new IllegalArgumentException("A status item requires a non-empty icon");
        }
        State resolvedState = state == null ? State.INACTIVE : state;
        ItemStack stack = icon.copy();
        stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(resolvedState.color(), ChatFormatting.BOLD));
        List<Component> compactLore = GuiTextService.compactLore(lore, 6);
        if (!compactLore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(compactLore));
        }
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, resolvedState.glint());
        return stack;
    }

    public enum State {
        ACTIONABLE(ChatFormatting.GREEN, true),
        IN_PROGRESS(ChatFormatting.YELLOW, false),
        COMPLETED(ChatFormatting.DARK_GREEN, false),
        PENDING(ChatFormatting.YELLOW, false),
        BLOCKED(ChatFormatting.RED, false),
        INACTIVE(ChatFormatting.GRAY, false),
        OWNED(ChatFormatting.AQUA, false),
        TEMPORARY(ChatFormatting.GOLD, false);

        private final ChatFormatting color;
        private final boolean glint;

        State(ChatFormatting color, boolean glint) {
            this.color = color;
            this.glint = glint;
        }

        public ChatFormatting color() {
            return color;
        }

        public boolean glint() {
            return glint;
        }
    }
}
