package dev.modmind.omnitools;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Standard vanilla Container backed solely by the session mirror. */
public final class CloudStorageSessionInventory implements Container {
    private final CloudStorageSession session;
    private final List<ItemStack> stacks;
    private final java.util.function.LongSupplier gameTick;

    public CloudStorageSessionInventory(CloudStorageSession session, List<ItemStack> initialPage,
                                        java.util.function.LongSupplier gameTick) {
        this.session = java.util.Objects.requireNonNull(session, "session");
        this.stacks = new ArrayList<>(CloudStorageSession.copyPage(initialPage));
        this.gameTick = gameTick == null ? () -> 0L : gameTick;
    }

    public CloudStorageSession session() { return session; }

    public List<ItemStack> snapshot() { return CloudStorageSession.copyPage(stacks); }

    @Override public int getContainerSize() { return CloudStorageData.SLOTS_PER_PAGE; }
    @Override public boolean isEmpty() { return stacks.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) { return valid(slot) ? stacks.get(slot) : ItemStack.EMPTY; }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (!valid(slot) || amount <= 0 || !session.canInteract()) return ItemStack.EMPTY;
        ItemStack removed = stacks.get(slot).split(amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (!valid(slot) || !session.canInteract()) return ItemStack.EMPTY;
        ItemStack result = stacks.set(slot, ItemStack.EMPTY);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!valid(slot) || !session.canInteract()) return;
        stacks.set(slot, stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        setChanged();
    }

    @Override public void setChanged() { session.markChanged(gameTick.getAsLong()); }
    @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) { return session.canInteract(); }
    @Override public void clearContent() {
        if (!session.canInteract()) return;
        for (int slot = 0; slot < stacks.size(); slot++) stacks.set(slot, ItemStack.EMPTY);
        setChanged();
    }

    private boolean valid(int slot) { return slot >= 0 && slot < stacks.size(); }
}
