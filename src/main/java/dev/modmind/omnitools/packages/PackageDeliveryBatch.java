package dev.modmind.omnitools.packages;

import net.minecraft.world.item.ItemStack;
import java.util.List;

/** Delivery result for one package open attempt. */
public record PackageDeliveryBatch(List<ItemStack> delivered, List<ItemStack> pending, boolean completed) {
    public PackageDeliveryBatch { delivered = List.copyOf(delivered == null ? List.of() : delivered); pending = List.copyOf(pending == null ? List.of() : pending); }
}
