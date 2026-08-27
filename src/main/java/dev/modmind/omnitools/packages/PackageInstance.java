package dev.modmind.omnitools.packages;

import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.UUID;

/** Persisted, immutable package grant snapshot including business quantities. */
public record PackageInstance(UUID instanceId, UUID ownerId, String packageId, int packageVersion,
                              String displayName, String iconId, PackageDefinition.Mode mode,
                              List<ItemStack> items, List<Long> quantities, String sourceEvent,
                              Status status, long grantedAt, int selectedItemIndex) {
    public PackageInstance {
        if (instanceId == null || ownerId == null) throw new IllegalArgumentException("Package instance ids required");
        packageId = packageId == null ? "" : packageId;
        displayName = displayName == null ? packageId : displayName;
        iconId = iconId == null ? "" : iconId;
        mode = mode == null ? PackageDefinition.Mode.ALL : mode;
        items = items == null ? List.of() : items.stream().map(ItemStack::copy).toList();
        quantities = quantities == null ? items.stream().map(stack -> (long) stack.getCount()).toList() : List.copyOf(quantities);
        if (quantities.size() != items.size()) throw new IllegalArgumentException("Package quantities must match items");
        for (long quantity : quantities) if (quantity < 1) throw new IllegalArgumentException("Package quantity must be positive");
        sourceEvent = sourceEvent == null ? "" : sourceEvent;
        status = status == null ? Status.PENDING : status;
    }
    public PackageInstance(UUID instanceId, UUID ownerId, String packageId, int packageVersion,
                           String displayName, String iconId, PackageDefinition.Mode mode,
                           List<ItemStack> items, String sourceEvent, Status status, long grantedAt,
                           int selectedItemIndex) {
        this(instanceId, ownerId, packageId, packageVersion, displayName, iconId, mode, items,
                items == null ? List.of() : items.stream().map(stack -> (long) stack.getCount()).toList(),
                sourceEvent, status, grantedAt, selectedItemIndex);
    }
    public List<ItemStack> items() { return items.stream().map(ItemStack::copy).toList(); }
    public enum Status { PENDING, OPENING, DELIVERING, WAITING_INBOX, OPENED, BLOCKED }
}
