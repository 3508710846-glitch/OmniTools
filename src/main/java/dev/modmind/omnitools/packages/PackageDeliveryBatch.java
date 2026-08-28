package dev.modmind.omnitools.packages;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable delivery transaction. Each entry is a logical package item, not a pre-expanded stack. */
public record PackageDeliveryBatch(UUID batchId, UUID packageInstanceId, List<StackEntry> stacks,
                                   int cursor, Status status, long createdAt, long updatedAt) {
    public PackageDeliveryBatch {
        if (batchId == null || packageInstanceId == null) throw new IllegalArgumentException("Package delivery batch ids are required");
        stacks = List.copyOf(stacks == null ? List.of() : stacks);
        if (cursor < 0 || cursor > stacks.size()) throw new IllegalArgumentException("Package delivery cursor is out of range");
        status = status == null ? Status.PENDING : status;
        if (createdAt < 0 || updatedAt < createdAt) throw new IllegalArgumentException("Package delivery timestamps are invalid");
    }

    /** Creates one logical entry per configured item. Physical stacks are created on demand. */
    public static PackageDeliveryBatch create(UUID packageInstanceId, List<ItemStack> stacks, long now) {
        List<StackEntry> entries = new ArrayList<>();
        for (ItemStack stack : stacks == null ? List.<ItemStack>of() : stacks) {
            entries.add(new StackEntry(UUID.randomUUID(), stack, stack.getCount(), 0L, StackStatus.PENDING));
        }
        return new PackageDeliveryBatch(UUID.randomUUID(), packageInstanceId, entries, 0, Status.PENDING, now, now);
    }

    /** Creates logical entries from immutable prototypes and business quantities. */
    public static PackageDeliveryBatch createLogical(UUID packageInstanceId, List<ItemStack> items,
                                                      List<Long> quantities, long now) {
        if (items == null || quantities == null || items.size() != quantities.size()) {
            throw new IllegalArgumentException("Package delivery entries are mismatched");
        }
        List<StackEntry> entries = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            entries.add(new StackEntry(UUID.randomUUID(), items.get(i), quantities.get(i), 0L, StackStatus.PENDING));
        }
        return new PackageDeliveryBatch(UUID.randomUUID(), packageInstanceId, entries, 0, Status.PENDING, now, now);
    }

    public PackageDeliveryBatch withStackStatus(UUID stackId, StackStatus nextStatus, long now) {
        List<StackEntry> updated = new ArrayList<>(stacks.size());
        boolean found = false;
        for (StackEntry entry : stacks) {
            if (entry.stackId().equals(stackId)) {
                updated.add(nextStatus == StackStatus.DELIVERED
                        ? entry.withProgress(entry.quantity(), nextStatus)
                        : entry.withStatus(nextStatus));
                found = true;
            } else updated.add(entry);
        }
        if (!found) throw new IllegalArgumentException("Unknown package delivery stack: " + stackId);
        return new PackageDeliveryBatch(batchId, packageInstanceId, updated, firstOutstandingCursor(updated), status, createdAt, Math.max(now, updatedAt));
    }

    public PackageDeliveryBatch withProgress(UUID stackId, long deliveredQuantity, StackStatus nextStatus, long now) {
        List<StackEntry> updated = new ArrayList<>(stacks.size());
        boolean found = false;
        for (StackEntry entry : stacks) {
            if (entry.stackId().equals(stackId)) {
                updated.add(entry.withProgress(deliveredQuantity, nextStatus));
                found = true;
            } else updated.add(entry);
        }
        if (!found) throw new IllegalArgumentException("Unknown package delivery stack: " + stackId);
        return new PackageDeliveryBatch(batchId, packageInstanceId, updated, firstOutstandingCursor(updated), status, createdAt, Math.max(now, updatedAt));
    }

    public PackageDeliveryBatch withStatus(Status nextStatus, long now) {
        return new PackageDeliveryBatch(batchId, packageInstanceId, stacks, firstOutstandingCursor(stacks), nextStatus, createdAt, Math.max(now, updatedAt));
    }

    public boolean hasUncertainDelivery() { return stacks.stream().anyMatch(entry -> entry.status() == StackStatus.DELIVERING); }
    public boolean hasBlockedStack() { return stacks.stream().anyMatch(entry -> entry.status() == StackStatus.BLOCKED); }
    public boolean isComplete() { return stacks.stream().allMatch(StackEntry::isComplete); }
    public List<ItemStack> pendingStacks() { return stacks.stream().filter(entry -> !entry.isComplete()).map(StackEntry::nextStack).toList(); }

    private static int firstOutstandingCursor(List<StackEntry> entries) {
        for (int i = 0; i < entries.size(); i++) if (!entries.get(i).isComplete()) return i;
        return entries.size();
    }

    public enum Status { PENDING, DELIVERING, WAITING_INBOX, COMPLETED, BLOCKED }
    public enum StackStatus { PENDING, DELIVERING, DELIVERED, WAITING_INBOX, BLOCKED }

    /** Immutable logical entry with an auditable delivered quantity. */
    public record StackEntry(UUID stackId, ItemStack itemSnapshot, long quantity, long deliveredQuantity, StackStatus status) {
        public StackEntry(UUID stackId, ItemStack stack, StackStatus status) {
            this(stackId, stack, stack == null ? 0 : stack.getCount(), 0L, status);
        }

        /** Compatibility constructor for old persisted batches where quantity was a physical stack count. */
        public StackEntry(UUID stackId, ItemStack snapshot, int quantity, StackStatus status) {
            this(stackId, snapshot, quantity, 0L, status);
        }

        public StackEntry {
            if (stackId == null) throw new IllegalArgumentException("Package delivery stack id is required");
            itemSnapshot = snapshot(itemSnapshot);
            if (quantity < 1 || quantity > 589824L) throw new IllegalArgumentException("Package delivery quantity is invalid");
            if (deliveredQuantity < 0 || deliveredQuantity > quantity) throw new IllegalArgumentException("Package delivered quantity is invalid");
            status = status == null ? StackStatus.PENDING : status;
            if (deliveredQuantity == quantity && status != StackStatus.BLOCKED && status != StackStatus.DELIVERING) status = StackStatus.DELIVERED;
        }

        @Override public ItemStack itemSnapshot() { return itemSnapshot.copy(); }
        public long remainingQuantity() { return quantity - deliveredQuantity; }
        public boolean isComplete() { return remainingQuantity() == 0 && status == StackStatus.DELIVERED; }
        public ItemStack nextStack() {
            ItemStack result = itemSnapshot.copy();
            result.setCount((int) Math.min(result.getMaxStackSize(), remainingQuantity()));
            return result;
        }
        /** Legacy-friendly view: returns the next physical stack to deliver. */
        public ItemStack stack() { return nextStack(); }
        public StackEntry withStatus(StackStatus nextStatus) { return new StackEntry(stackId, itemSnapshot, quantity, deliveredQuantity, nextStatus); }
        public StackEntry withProgress(long nextDelivered, StackStatus nextStatus) { return new StackEntry(stackId, itemSnapshot, quantity, nextDelivered, nextStatus); }

        private static ItemStack snapshot(ItemStack stack) {
            if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Package delivery stack cannot be empty");
            ItemStack snapshot = stack.copy(); snapshot.setCount(1); return snapshot;
        }
    }
}
