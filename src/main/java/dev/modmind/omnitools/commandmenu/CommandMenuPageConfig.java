package dev.modmind.omnitools.commandmenu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/** Parsed contents of one independent menu JSON file. */
public record CommandMenuPageConfig(String id, Component title, int size, ItemStack filler,
                                    Map<Integer, CommandMenuItem> items, List<Component> fillerLore) {
    public CommandMenuPageConfig {
        filler = filler.copy();
        items = Map.copyOf(items);
        fillerLore = List.copyOf(fillerLore);
    }

    public int rows() {
        return size / 9;
    }

    public ItemStack fillerStack() {
        return filler.copy();
    }
}
