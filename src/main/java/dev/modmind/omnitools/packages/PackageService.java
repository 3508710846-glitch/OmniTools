package dev.modmind.omnitools.packages;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Server-authoritative creation and durable, conservative opening of virtual packages. */
public final class PackageService {
    public enum Result { CREATED, OPENED, WAITING_INBOX, BLOCKED, NOT_FOUND, INVALID, LIMIT_REACHED }

    public record OpenResult(Result result, PackageInstance instance, List<ItemStack> delivered,
                             List<ItemStack> pending) {
    }

    public Optional<String> preflightCreate(MinecraftServer server, UUID owner, String packageId, int amount) {
        if (server == null || owner == null || amount < 1) return Optional.of("invalid arguments");
        PackageConfig config = dev.modmind.omnitools.ModMindEntry.configSnapshot().packages();
        if (config.definition(packageId).isEmpty()) return Optional.of("unknown package: " + packageId);
        long pending = PackageData.get(server).list(owner).stream()
                .filter(instance -> instance.status() != PackageInstance.Status.OPENED).count();
        if (pending + amount > config.settings().maxPendingPackagesPerPlayer()) {
            return Optional.of("pending package limit reached (" + pending + "+" + amount + ")");
        }
        return Optional.empty();
    }

    public PackageInstance create(MinecraftServer server, UUID owner, String packageId, String sourceEvent) {
        return create(server, owner, packageId, sourceEvent, "");
    }

    /**
     * Creates or reuses an instance for a stable reward grant key. A non-empty key is the
     * idempotency boundary between the reward ledger and package SavedData.
     */
    public PackageInstance create(MinecraftServer server, UUID owner, String packageId, String sourceEvent,
                                  String grantKey) {
        if (server == null || owner == null) {
            throw new IllegalArgumentException("server and owner are required");
        }
        PackageData data = PackageData.get(server);
        if (grantKey != null && !grantKey.isBlank()) {
            Optional<PackageInstance> existing = data.findByGrantKey(owner, grantKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        PackageConfig config = dev.modmind.omnitools.ModMindEntry.configSnapshot().packages();
        PackageDefinition definition = config.definition(packageId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown package: " + packageId));
        if (data.list(owner).stream().filter(instance -> instance.status() != PackageInstance.Status.OPENED).count()
                >= config.settings().maxPackagesPerPlayer()) {
            throw new IllegalStateException("Package limit reached");
        }
        List<ItemStack> snapshot = definition.items().stream().map(PackageItem::prototype).toList();
        List<Long> quantities = definition.items().stream().map(PackageItem::quantity).toList();
        PackageInstance instance = new PackageInstance(UUID.randomUUID(), owner, definition.id(), definition.version(),
                definition.display(), definition.description(), definition.iconId(), definition.mode(), snapshot, quantities, sourceEvent,
                grantKey, PackageInstance.Status.PENDING, System.currentTimeMillis(), -1);
        return data.createIfAbsent(instance);
    }

    public OpenResult open(ServerPlayer player, UUID instanceId) {
        if (player == null || instanceId == null) {
            return result(Result.INVALID, null, List.of(), List.of());
        }
        PackageData data = PackageData.get(player.level().getServer());
        PackageInstance instance = data.find(player.getUUID(), instanceId).orElse(null);
        if (instance == null) {
            return result(Result.NOT_FOUND, null, List.of(), List.of());
        }
        if (instance.status() == PackageInstance.Status.OPENED) {
            return result(Result.INVALID, instance, List.of(), List.of());
        }
        if (instance.status() == PackageInstance.Status.BLOCKED) {
            return result(Result.BLOCKED, instance, List.of(), instance.items());
        }

        PackageDeliveryBatch batch = data.findDeliveryBatch(instance.instanceId()).orElse(null);
        if (batch != null && (batch.status() == PackageDeliveryBatch.Status.BLOCKED
                || batch.hasUncertainDelivery() || batch.hasBlockedStack())) {
            return block(data, player.level().getServer(), instance, batch);
        }
        if (batch != null && (batch.status() == PackageDeliveryBatch.Status.COMPLETED || batch.isComplete())) {
            PackageDeliveryBatch completed = batch.withStatus(PackageDeliveryBatch.Status.COMPLETED,
                    System.currentTimeMillis());
            data.updateDeliveryBatch(completed);
            PackageInstance opened = instance.withStatus(PackageInstance.Status.OPENED);
            data.update(opened);
            return result(Result.OPENED, opened, List.of(), List.of());
        }

        try {
            if (batch == null) {
                PackageInstance opening = lockSelection(data, instance);
                if (opening.mode() == PackageDefinition.Mode.RANDOM_ONE) data.flush(player.level().getServer());
                batch = data.createDeliveryBatchIfAbsent(PackageDeliveryBatch.createLogical(opening.instanceId(),
                        selectedPayload(opening).items(), selectedPayload(opening).quantities(), System.currentTimeMillis()));
                instance = opening;
            }
            if (instance.status() != PackageInstance.Status.DELIVERING && instance.status() != PackageInstance.Status.WAITING_INBOX) {
                instance = instance.withStatus(PackageInstance.Status.DELIVERING);
                data.update(instance);
                data.flush(player.level().getServer());
            }
        } catch (RuntimeException exception) {
            System.err.println("[omnitools] Package delivery setup was blocked: instance=" + instance.instanceId()
                    + " (" + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")");
            return block(data, player.level().getServer(), instance, batch);
        }
        return result(Result.CREATED, instance, List.of(), batch.pendingStacks());
    }

    /** Advances durable package deliveries on the server thread with a bounded per-tick budget. */
    public void tick(MinecraftServer server) {
        if (server == null) return;
        PackageData data = PackageData.get(server);
        int budget = dev.modmind.omnitools.ModMindEntry.configSnapshot().packages().settings().deliveryStacksPerTick();
        for (PackageDeliveryBatch batch : data.listDeliveryBatches()) {
            if (budget <= 0) break;
            PackageInstance instance = data.findByInstanceId(batch.packageInstanceId()).orElse(null);
            if (instance == null || instance.status() == PackageInstance.Status.OPENED || instance.status() == PackageInstance.Status.BLOCKED) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(instance.ownerId());
            if (player == null) continue;
            int used = deliver(data, player, instance, batch, budget);
            budget -= used;
        }
    }

    private static PackageInstance lockSelection(PackageData data, PackageInstance instance) {
        if (instance.items().isEmpty()) {
            throw new IllegalStateException("package has no item snapshots");
        }
        if (instance.mode() != PackageDefinition.Mode.RANDOM_ONE) {
            PackageInstance opening = instance.withStatus(PackageInstance.Status.OPENING);
            data.update(opening);
            return opening;
        }
        int selected = instance.selectedItemIndex();
        int entryCount = instance.items().size();
        if (selected < 0) {
            selected = ThreadLocalRandom.current().nextInt(entryCount);
        } else if (entryCount > 1 && selected >= entryCount) {
            throw new IllegalStateException("random package selection is out of range");
        }
        PackageInstance opening = instance.withOpeningSelection(selected);
        data.update(opening);
        return opening;
    }

    private static Payload selectedPayload(PackageInstance instance) {
        List<ItemStack> items = instance.items();
        List<Long> quantities = instance.quantities();
        if (items.size() != quantities.size() || items.isEmpty()) {
            throw new IllegalStateException("package snapshot is invalid");
        }
        if (instance.mode() != PackageDefinition.Mode.RANDOM_ONE) {
            return new Payload(items, quantities);
        }
        int selected = instance.selectedItemIndex();
        if (selected < 0) {
            throw new IllegalStateException("random package selection was not persisted");
        }
        // Older interrupted opens replaced the snapshot with the chosen item. Preserve that entry
        // rather than treating the old original index as an error during migration to batch data.
        int index = items.size() == 1 ? 0 : selected;
        if (index >= items.size()) {
            throw new IllegalStateException("random package selection is out of range");
        }
        return new Payload(List.of(items.get(index)), List.of(quantities.get(index)));
    }

    private static int deliver(PackageData data, ServerPlayer player, PackageInstance instance,
                               PackageDeliveryBatch batch, int budget) {
        List<ItemStack> delivered = new ArrayList<>();
        int used = 0;
        try {
            while (used < budget && !batch.isComplete()) {
                PackageDeliveryBatch.StackEntry entry = batch.stacks().stream().filter(e -> !e.isComplete()).findFirst().orElse(null);
                if (entry == null) break;
                if (entry.status() == PackageDeliveryBatch.StackStatus.DELIVERING || entry.status() == PackageDeliveryBatch.StackStatus.BLOCKED) {
                    block(data, player.level().getServer(), instance, batch); return used;
                }
                ItemStack stack = entry.nextStack();
                if (stack.isEmpty()) {
                    batch = batch.withProgress(entry.stackId(), entry.quantity(), PackageDeliveryBatch.StackStatus.DELIVERED, System.currentTimeMillis());
                    data.updateDeliveryBatch(batch); continue;
                }
                if (!canFitFully(player, stack)) {
                    if (entry.status() != PackageDeliveryBatch.StackStatus.WAITING_INBOX) {
                        batch = batch.withStackStatus(entry.stackId(), PackageDeliveryBatch.StackStatus.WAITING_INBOX,
                                System.currentTimeMillis()).withStatus(PackageDeliveryBatch.Status.WAITING_INBOX,
                                System.currentTimeMillis());
                        data.updateDeliveryBatch(batch);
                        data.flush(player.level().getServer());
                    }
                    break;
                }

                // Persist the ambiguous boundary before changing player inventory.
                batch = batch.withStackStatus(entry.stackId(), PackageDeliveryBatch.StackStatus.DELIVERING,
                        System.currentTimeMillis()).withStatus(PackageDeliveryBatch.Status.DELIVERING,
                        System.currentTimeMillis());
                if (instance.status() != PackageInstance.Status.DELIVERING) {
                    instance = instance.withStatus(PackageInstance.Status.DELIVERING);
                    data.update(instance);
                }
                data.updateDeliveryBatch(batch);
                data.flush(player.level().getServer());
                insertFully(player, stack);
                long nextDelivered = entry.deliveredQuantity() + stack.getCount();
                PackageDeliveryBatch.StackStatus nextStatus = nextDelivered >= entry.quantity()
                        ? PackageDeliveryBatch.StackStatus.DELIVERED : PackageDeliveryBatch.StackStatus.PENDING;
                batch = batch.withProgress(entry.stackId(), nextDelivered, nextStatus, System.currentTimeMillis());
                data.updateDeliveryBatch(batch);
                data.flush(player.level().getServer());
                delivered.add(stack);
                used++;
            }
        } catch (RuntimeException exception) {
            System.err.println("[omnitools] Package delivery outcome is unknown and was blocked: instance="
                    + instance.instanceId() + " (" + exception.getClass().getSimpleName() + ": "
                    + exception.getMessage() + ")");
            block(data, player.level().getServer(), instance, batch); return used;
        }

        if (batch.isComplete()) {
            batch = batch.withStatus(PackageDeliveryBatch.Status.COMPLETED, System.currentTimeMillis());
            data.updateDeliveryBatch(batch);
            PackageInstance opened = instance.withStatus(PackageInstance.Status.OPENED);
            data.update(opened);
            data.flush(player.level().getServer());
            return used;
        }

        batch = batch.withStatus(PackageDeliveryBatch.Status.WAITING_INBOX, System.currentTimeMillis());
        data.updateDeliveryBatch(batch);
        PackageInstance waiting = instance.withStatus(PackageInstance.Status.WAITING_INBOX);
        data.update(waiting);
        data.flush(player.level().getServer());
        return used;
    }

    private static OpenResult block(PackageData data, MinecraftServer server, PackageInstance instance,
                                    PackageDeliveryBatch batch) {
        PackageDeliveryBatch blocked = batch;
        if (batch != null) {
            long now = System.currentTimeMillis();
            for (PackageDeliveryBatch.StackEntry entry : batch.stacks()) {
                if (entry.status() != PackageDeliveryBatch.StackStatus.DELIVERED) {
                    blocked = blocked.withStackStatus(entry.stackId(), PackageDeliveryBatch.StackStatus.BLOCKED, now);
                }
            }
            blocked = blocked.withStatus(PackageDeliveryBatch.Status.BLOCKED, now);
            data.updateDeliveryBatch(blocked);
        }
        PackageInstance blockedInstance = instance.withStatus(PackageInstance.Status.BLOCKED);
        data.update(blockedInstance);
        try {
            data.flush(server);
        } catch (RuntimeException exception) {
            System.err.println("[omnitools] Package blocked state could not be flushed: instance="
                    + instance.instanceId() + " (" + exception.getClass().getSimpleName() + ": "
                    + exception.getMessage() + ")");
        }
        List<ItemStack> pending = blocked == null ? instance.items() : remainingStacks(blocked);
        return result(Result.BLOCKED, blockedInstance, List.of(), pending);
    }

    private static List<ItemStack> remainingStacks(PackageDeliveryBatch batch) {
        return batch.stacks().stream()
                .filter(entry -> entry.status() != PackageDeliveryBatch.StackStatus.DELIVERED)
                .map(PackageDeliveryBatch.StackEntry::stack)
                .toList();
    }

    private static List<ItemStack> split(Payload payload) {
        List<ItemStack> result = new ArrayList<>();
        for (int index = 0; index < payload.items().size(); index++) {
            ItemStack source = payload.items().get(index);
            long remaining = payload.quantities().get(index);
            int max = Math.max(1, source.getMaxStackSize());
            while (remaining > 0) {
                ItemStack stack = source.copy();
                int amount = (int) Math.min(max, remaining);
                stack.setCount(amount);
                result.add(stack);
                remaining -= amount;
            }
        }
        return List.copyOf(result);
    }

    private static boolean canFitFully(ServerPlayer player, ItemStack reward) {
        ItemStack simulated = reward.copy();
        var inventory = player.getInventory();
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()) && !simulated.isEmpty(); slot++) {
            ItemStack present = inventory.getItem(slot);
            if (present.isEmpty()) {
                simulated.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(present, simulated)) {
                simulated.shrink(Math.min(simulated.getCount(),
                        Math.max(0, present.getMaxStackSize() - present.getCount())));
            }
        }
        return simulated.isEmpty();
    }

    private static void insertFully(ServerPlayer player, ItemStack reward) {
        ItemStack actual = reward.copy();
        var inventory = player.getInventory();
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()) && !actual.isEmpty(); slot++) {
            ItemStack present = inventory.getItem(slot);
            if (present.isEmpty()) {
                int amount = Math.min(actual.getCount(), actual.getMaxStackSize());
                ItemStack placed = actual.copy();
                placed.setCount(amount);
                inventory.setItem(slot, placed);
                actual.shrink(amount);
            } else if (ItemStack.isSameItemSameComponents(present, actual)) {
                int amount = Math.min(actual.getCount(),
                        Math.max(0, present.getMaxStackSize() - present.getCount()));
                if (amount > 0) {
                    present.grow(amount);
                    actual.shrink(amount);
                }
            }
        }
        if (!actual.isEmpty()) {
            throw new IllegalStateException("package inventory delivery was not complete");
        }
    }

    private static OpenResult result(Result result, PackageInstance instance, List<ItemStack> delivered,
                                     List<ItemStack> pending) {
        return new OpenResult(result, instance, List.copyOf(delivered), List.copyOf(pending));
    }

    private record Payload(List<ItemStack> items, List<Long> quantities) {
    }
}
