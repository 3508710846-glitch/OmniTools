package dev.modmind.omnitools.packages;

import net.minecraft.world.item.ItemStack;

/** Immutable item prototype plus business quantity (which may exceed one stack). */
public record PackageItem(String id, ItemStack prototype, long quantity) {
    public PackageItem {
        id = id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.isBlank() || !id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid package item id: " + id);
        if (prototype == null || prototype.isEmpty()) throw new IllegalArgumentException("Package item prototype cannot be empty");
        prototype = prototype.copy();
        if (prototype.getCount() < 1 || prototype.getCount() > 64) throw new IllegalArgumentException("Package item prototype count must be between 1 and 64");
        if (quantity < 1) throw new IllegalArgumentException("Package item quantity must be positive");
    }
    public ItemStack prototype() { return prototype.copy(); }
}
