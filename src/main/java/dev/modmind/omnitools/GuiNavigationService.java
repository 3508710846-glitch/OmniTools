package dev.modmind.omnitools;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared vanilla navigation items. */
public final class GuiNavigationService {
    private GuiNavigationService() {
    }

    public static ItemStack previous() {
        return GuiTheme.navigation(Items.ARROW, ServerText.translatable("gui.omnitools.rewards.previous"),
                ServerText.translatable("gui.omnitools.gui.previous_hint"));
    }

    public static ItemStack next() {
        return GuiTheme.navigation(Items.ARROW, ServerText.translatable("gui.omnitools.rewards.next"),
                ServerText.translatable("gui.omnitools.gui.next_hint"));
    }

    public static ItemStack page(int current, int total, int count) {
        return GuiTheme.named(Items.PAPER, GuiTextService.page(current, total, count),
                java.util.List.of(ServerText.translatable("gui.omnitools.gui.page_hint")));
    }

    public static ItemStack close() {
        return GuiTheme.navigation(Items.BARRIER, ServerText.translatable("gui.omnitools.checkin.close"),
                ServerText.translatable("gui.omnitools.checkin.close_hint"));
    }
}
