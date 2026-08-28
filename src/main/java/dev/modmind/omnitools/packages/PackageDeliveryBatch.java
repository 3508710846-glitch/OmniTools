package dev.modmind.omnitools.packages;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Durable delivery transaction for one virtual package instance.
 *
 * <p>Each entry is one already-split inventory stack. The entry is recorded as {@code DELIVERING}
 * before an inventory mutation; an interruption at that boundary is deliberately quarantined
 * instead of replaying an item whose delivery outcome cannot be proven.</p>
 */
public record PackageDeliveryBatch(UUID batchId, UUID packageInstanceId, List<StackEntry> stacks,
                                   int cursor, Status status, long createdAt, long updatedAt) {
    public PackageDeliveryBatch {
        if (batchId == null || packageInstanceId == null) {
            throw new IllegalArgumentException("Package delivery batch ids are required");
        }
        stacks = List.copyOf(stacks == null ? List.of() : stacks);
        if (cursor < 0 || cursor > stacks.size()) {
            throw new IllegalArgumentException("Package delivery cursor is out of range");
        }
        status = status == null ? Status.PENDING : status;
        if (createdAt < 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("Package delivery timestamps are invalid");
        }
    }

    public static PackageDeliveryBatch create(UUID packageInstanceId, List<ItemStack> stacks, long now) {
        List<StackEntry> entries = new ArrayList<>();
        for (ItemStack stack : stacks == null ? List.<ItemStack>of() : stacks) {
            entries.add(new StackEntry(UUID.randomUUID(), stack, StackStatus.PENDING));
        }
        return new PackageDeliveryBatch(UUID.randomUUID(), packageInstanceId, entries, 0,
                Status.PENDING, now, now);
    }

    public PackageDeliveryBatch withStackStatus(UUID stackId, StackStatus stackStatus, long now) {
        List<StackEntry> updated = new ArrayList<>(stacks.size());
        boolean found = false;
        for (StackEntry entry : stacks) {
            if (entry.stackId().equals(stackId)) {
                updated.add(entry.withStatus(stackStatus));
                found = true;
            } else {
                updated.add(entry);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Unknown package delivery stack: " + stackId);
        }
        return new PackageDeliveryBatch(batchId, packageInstanceId, updated, firstOutstandingCursor(updated),
                status, createdAt, Math.max(now, updatedAt));
    }

    public PackageDeliveryBatch withStatus(Status nextStatus, long now) {
        return new PackageDeliveryBatch(batchId, packageInstanceId, stacks, firstOutstandingCursor(stacks),
                nextStatus, createdAt, Math.max(now, updatedAt));
    }

    public boolean hasUncertainDelivery() {
        return stacks.stream().anyMatch(entry -> entry.status() == StackStatus.DELIVERING);
    }

    public boolean hasBlockedStack() {
        return stacks.stream().anyMatch(entry -> entry.status() == StackStatus.BLOCKED);
    }

    public boolean isComplete() {
        return stacks.stream().allMatch(entry -> entry.status() == StackStatus.DELIVERED);
    }

    public List<ItemStack> pendingStacks() {
        return stacks.stream()
                .filter(entry -> entry.status() == StackStatus.PENDING || entry.status() == StackStatus.WAITING_INBOX)
                .map(StackEntry::stack)
                .toList();
    }

    private static int firstOutstandingCursor(List<StackEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).status() != StackStatus.DELIVERED) {
                return index;
            }
        }
        return entries.size();
    }

    public enum Status {
        PENDING,
        DELIVERING,
        WAITING_INBOX,
        COMPLETED,
        BLOCKED
    }

    public enum StackStatus {
        PENDING,
        DELIVERING,
        DELIVERED,
        WAITING_INBOX,
        BLOCKED
    }

    /** A single split stack snapshot and its durable delivery state. */
    public record StackEntry(UUID stackId, ItemStack itemSnapshot, int quantity, StackStatus status) {
        public StackEntry(UUID stackId, ItemStack stack, StackStatus status) {
            this(stackId, snapshot(stack), quantity(stack), status);
        }

        public StackEntry {
            if (stackId == null) {
                throw new IllegalArgumentException("Package delivery stack id is required");
            }
            itemSnapshot = snapshot(itemSnapshot);
            if (quantity < 1 || quantity > itemSnapshot.getMaxStackSize()) {
                throw new IllegalArgumentException("Package delivery stack quantity is invalid");
            }
            status = status == null ? StackStatus.PENDING : status;
        }

        @Override
        public ItemStack itemSnapshot() {
            return itemSnapshot.copy();
        }

        public ItemStack stack() {
            ItemStack result = itemSnapshot.copy();
            result.setCount(quantity);
            return result;
        }

        public StackEntry withStatus(StackStatus nextStatus) {
            return new StackEntry(stackId, itemSnapshot, quantity, nextStatus);
        }

        private static ItemStack snapshot(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                throw new IllegalArgumentException("Package delivery stack cannot be empty");
            }
            ItemStack snapshot = stack.copy();
            snapshot.setCount(1);
            return snapshot;
        }

        private static int quantity(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                throw new IllegalArgumentException("Package delivery stack cannot be empty");
            }
            return stack.getCount();
        }
    }
}
