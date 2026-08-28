package dev.modmind.omnitools.packages;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Server-authoritative package creation and opening service. */
public final class PackageService {
    public enum Result { CREATED, OPENED, WAITING_INBOX, BLOCKED, NOT_FOUND, INVALID, LIMIT_REACHED }
    public record OpenResult(Result result, PackageInstance instance, List<ItemStack> delivered, List<ItemStack> pending) {}

    public PackageInstance create(MinecraftServer server, UUID owner, String packageId, String sourceEvent) {
        return create(server, owner, packageId, sourceEvent, "");
    }

    /**
     * Creates or reuses an instance for a stable reward grant key. A non-empty key is the
     * idempotency boundary between the reward ledger and package SavedData.
     */
    public PackageInstance create(MinecraftServer server, UUID owner, String packageId, String sourceEvent,
                                   String grantKey) {
        if (server == null || owner == null) throw new IllegalArgumentException("server and owner are required");
        PackageData data = PackageData.get(server);
        if (grantKey != null && !grantKey.isBlank()) {
            Optional<PackageInstance> existing = data.findByGrantKey(owner, grantKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        PackageConfig config = dev.modmind.omnitools.ModMindEntry.configSnapshot().packages();
        PackageDefinition definition = config.definition(packageId).orElseThrow(() -> new IllegalArgumentException("Unknown package: " + packageId));
        if (data.list(owner).stream().filter(p -> p.status() != PackageInstance.Status.OPENED).count() >= config.settings().maxPackagesPerPlayer()) {
            throw new IllegalStateException("Package limit reached");
        }
        List<ItemStack> snapshot = definition.items().stream().map(item -> item.prototype()).toList();
        List<Long> quantities = definition.items().stream().map(PackageItem::quantity).toList();
        PackageInstance instance = new PackageInstance(UUID.randomUUID(), owner, definition.id(), definition.version(),
                definition.display(), definition.iconId(), definition.mode(), snapshot, quantities, sourceEvent,
                grantKey,
                PackageInstance.Status.PENDING, System.currentTimeMillis(), -1);
        return data.createIfAbsent(instance);
    }

    public OpenResult open(ServerPlayer player, UUID instanceId) {
        if (player == null || instanceId == null) return new OpenResult(Result.INVALID, null, List.of(), List.of());
        PackageData data = PackageData.get(player.level().getServer());
        PackageInstance current = data.find(player.getUUID(), instanceId).orElse(null);
        if (current == null) return new OpenResult(Result.NOT_FOUND, null, List.of(), List.of());
        if (current.status() == PackageInstance.Status.OPENED) return new OpenResult(Result.INVALID, current, List.of(), List.of());
        if (current.status() == PackageInstance.Status.OPENING || current.status() == PackageInstance.Status.DELIVERING) {
            return new OpenResult(Result.BLOCKED, current, List.of(), current.items());
        }
        if (current.status() == PackageInstance.Status.WAITING_INBOX) {
            List<ItemStack> delivered = new ArrayList<>();
            List<ItemStack> pending = new ArrayList<>();
            for (ItemStack stack : current.items()) {
                if (canFitFully(player, stack)) { insertFully(player, stack); delivered.add(stack); }
                else pending.add(stack);
            }
            PackageInstance updated = new PackageInstance(current.instanceId(), current.ownerId(), current.packageId(), current.packageVersion(), current.displayName(), current.iconId(), current.mode(), pending, pending.stream().map(s -> (long) s.getCount()).toList(), current.sourceEvent(), current.grantKey(), pending.isEmpty() ? PackageInstance.Status.OPENED : PackageInstance.Status.WAITING_INBOX, current.grantedAt(), current.selectedItemIndex());
            data.update(updated);
            return new OpenResult(pending.isEmpty() ? Result.OPENED : Result.WAITING_INBOX, updated, delivered, pending);
        }
        if (current.status() == PackageInstance.Status.BLOCKED) return new OpenResult(Result.BLOCKED, current, List.of(), current.items());
        List<ItemStack> payload = current.items();
        List<Long> quantities = current.quantities();
        int selected = current.selectedItemIndex();
        if (current.mode() == PackageDefinition.Mode.RANDOM_ONE) {
            if (selected < 0 || (selected >= payload.size() && payload.size() > 1)) {
                selected = new Random().nextInt(payload.size());
                ItemStack chosen = payload.get(selected).copy(); chosen.setCount(1);
                payload = List.of(chosen);
                quantities = List.of(quantities.get(selected));
                current = new PackageInstance(current.instanceId(), current.ownerId(), current.packageId(), current.packageVersion(), current.displayName(), current.iconId(), current.mode(), payload, quantities, current.sourceEvent(), current.grantKey(), PackageInstance.Status.OPENING, current.grantedAt(), selected);
                data.update(current);
            } else {
                int index = payload.size() == 1 ? 0 : selected;
                ItemStack chosen = payload.get(index).copy();
                payload = List.of(chosen);
                quantities = List.of(quantities.get(index));
            }
        } else {
            current = new PackageInstance(current.instanceId(), current.ownerId(), current.packageId(), current.packageVersion(), current.displayName(), current.iconId(), current.mode(), payload, quantities, current.sourceEvent(), current.grantKey(), PackageInstance.Status.OPENING, current.grantedAt(), selected);
            data.update(current);
        }
        List<ItemStack> stacks = split(payload, quantities);
        List<ItemStack> pending = new ArrayList<>();
        List<ItemStack> delivered = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (canFitFully(player, stack)) { insertFully(player, stack); delivered.add(stack); }
            else pending.add(stack);
        }
        PackageInstance.Status status = pending.isEmpty() ? PackageInstance.Status.OPENED : PackageInstance.Status.WAITING_INBOX;
        List<ItemStack> persistedItems = pending.isEmpty() ? List.of() : List.copyOf(pending);
        List<Long> stackQuantities = persistedItems.stream().map(stack -> (long) stack.getCount()).toList();
        PackageInstance updated = new PackageInstance(current.instanceId(), current.ownerId(), current.packageId(), current.packageVersion(), current.displayName(), current.iconId(), current.mode(), persistedItems, stackQuantities, current.sourceEvent(), current.grantKey(), status, current.grantedAt(), current.selectedItemIndex());
        data.update(updated);
        return new OpenResult(pending.isEmpty() ? Result.OPENED : Result.WAITING_INBOX, updated, delivered, pending);
    }

    private static List<ItemStack> split(List<ItemStack> payload, List<Long> quantities) {
        List<ItemStack> result = new ArrayList<>();
        for (int index = 0; index < payload.size(); index++) {
            ItemStack source = payload.get(index);
            long remaining = quantities.get(index);
            int max = Math.max(1, source.getMaxStackSize());
            while (remaining > 0) { ItemStack stack = source.copy(); int amount = (int) Math.min(max, remaining); stack.setCount(amount); result.add(stack); remaining -= amount; }
        }
        return List.copyOf(result);
    }
    private static boolean canFitFully(ServerPlayer player, ItemStack reward) {
        ItemStack simulated = reward.copy(); var inv = player.getInventory();
        for (int slot = 0; slot < Math.min(36, inv.getContainerSize()) && !simulated.isEmpty(); slot++) {
            ItemStack present = inv.getItem(slot);
            if (present.isEmpty()) simulated.setCount(0);
            else if (ItemStack.isSameItemSameComponents(present, simulated)) simulated.shrink(Math.min(simulated.getCount(), Math.max(0, present.getMaxStackSize() - present.getCount())));
        }
        return simulated.isEmpty();
    }
    private static void insertFully(ServerPlayer player, ItemStack reward) {
        ItemStack actual = reward.copy(); var inv = player.getInventory();
        for (int slot = 0; slot < Math.min(36, inv.getContainerSize()) && !actual.isEmpty(); slot++) {
            ItemStack present = inv.getItem(slot);
            if (present.isEmpty()) { int n = Math.min(actual.getCount(), actual.getMaxStackSize()); ItemStack placed = actual.copy(); placed.setCount(n); inv.setItem(slot, placed); actual.shrink(n); }
            else if (ItemStack.isSameItemSameComponents(present, actual)) { int n = Math.min(actual.getCount(), Math.max(0, present.getMaxStackSize() - present.getCount())); if (n > 0) { present.grow(n); actual.shrink(n); } }
        }
    }
}
