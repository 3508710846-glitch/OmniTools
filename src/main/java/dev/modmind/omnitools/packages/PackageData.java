package dev.modmind.omnitools.packages;

import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.reward.RewardClaimLedger;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** World-persistent virtual package instances. */
public final class PackageData extends SavedData {
    private static final String ID = ModMindEntry.MOD_ID + "_packages";
    private static final ThreadLocal<HolderLookup.Provider> LOADING_REGISTRIES = new ThreadLocal<>();

    public static final SavedDataType<PackageData> TYPE = new SavedDataType<>(ID, PackageData::new,
            CompoundTag.CODEC.xmap(PackageData::fromTag, PackageData::toTag), DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, LinkedHashMap<UUID, PackageInstance>> instances = new HashMap<>();
    /** Raw records that could not be decoded; they are retained for administrator recovery. */
    private final Map<UUID, LinkedHashMap<UUID, CompoundTag>> corruptRecords = new HashMap<>();
    /** Records without a valid UUID cannot become instances, but still must survive a later save. */
    private final Map<UUID, LinkedHashMap<String, CompoundTag>> malformedInstanceRecords = new HashMap<>();
    private final Map<String, CompoundTag> malformedOwnerRecords = new LinkedHashMap<>();
    /** One active or completed delivery transaction per virtual package instance. */
    private final Map<UUID, PackageDeliveryBatch> deliveryBatches = new LinkedHashMap<>();
    /** Raw batch records that could not be decoded; retained alongside the blocked instance. */
    private final Map<UUID, CompoundTag> corruptBatchRecords = new LinkedHashMap<>();
    private final Map<String, CompoundTag> malformedBatchRecords = new LinkedHashMap<>();
    private HolderLookup.Provider registries;
    private boolean deliveryRecoveryPending;

    public static PackageData get(MinecraftServer server) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            throw new IllegalStateException("overworld unavailable");
        }
        LOADING_REGISTRIES.set(server.registryAccess());
        try {
            PackageData data = level.getDataStorage().computeIfAbsent(TYPE);
            data.registries = server.registryAccess();
            data.reconcileDeliveryBatches();
            return data;
        } finally {
            LOADING_REGISTRIES.remove();
        }
    }

    public synchronized PackageInstance add(PackageInstance instance) {
        instances.computeIfAbsent(instance.ownerId(), ignored -> new LinkedHashMap<>())
                .put(instance.instanceId(), instance);
        corruptRecords.computeIfAbsent(instance.ownerId(), ignored -> new LinkedHashMap<>())
                .remove(instance.instanceId());
        setDirty();
        return instance;
    }

    /**
     * Atomically reuses a previously-created reward instance with the same grant key, or stores
     * the candidate. Blank keys are intentionally not deduplicated for administrator grants.
     */
    public synchronized PackageInstance createIfAbsent(PackageInstance candidate) {
        if (!candidate.grantKey().isBlank()) {
            Optional<PackageInstance> existing = findByGrantKey(candidate.ownerId(), candidate.grantKey());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return add(candidate);
    }

    public synchronized Optional<PackageInstance> find(UUID owner, UUID id) {
        var map = instances.get(owner);
        return Optional.ofNullable(map == null ? null : map.get(id));
    }

    public synchronized Optional<PackageInstance> findByGrantKey(UUID owner, String grantKey) {
        if (owner == null || grantKey == null || grantKey.isBlank()) {
            return Optional.empty();
        }
        var map = instances.get(owner);
        if (map == null) {
            return Optional.empty();
        }
        return map.values().stream().filter(instance -> grantKey.equals(instance.grantKey())).findFirst();
    }

    public synchronized List<PackageInstance> list(UUID owner) {
        var map = instances.get(owner);
        return map == null ? List.of() : List.copyOf(map.values());
    }

    public synchronized Optional<PackageDeliveryBatch> findDeliveryBatch(UUID packageInstanceId) {
        return Optional.ofNullable(deliveryBatches.get(packageInstanceId));
    }

    public synchronized List<PackageDeliveryBatch> listDeliveryBatches() {
        return List.copyOf(deliveryBatches.values());
    }

    /** Removes only safe, old completed history. Pending, waiting and blocked assets are retained. */
    public synchronized int cleanupHistory(MinecraftServer server, int retentionDays) {
        long cutoff = System.currentTimeMillis() - Math.max(1L, retentionDays) * 86_400_000L;
        int removed = 0;
        for (UUID owner : List.copyOf(instances.keySet())) {
            LinkedHashMap<UUID, PackageInstance> map = instances.get(owner);
            for (PackageInstance instance : List.copyOf(map.values())) {
                if (instance.status() != PackageInstance.Status.OPENED || instance.grantedAt() > cutoff) continue;
                PackageDeliveryBatch batch = deliveryBatches.get(instance.instanceId());
                if (batch != null && !batch.isComplete()) continue;
                if (!instance.grantKey().isBlank() && !RewardClaimLedger.isGrantedGrantKey(server, owner, instance.grantKey())) continue;
                map.remove(instance.instanceId());
                deliveryBatches.remove(instance.instanceId());
                removed++;
            }
            if (map.isEmpty()) instances.remove(owner);
        }
        if (removed > 0) setDirty();
        return removed;
    }

    public synchronized Optional<PackageInstance> findByInstanceId(UUID instanceId) {
        return Optional.ofNullable(findByInstanceIdInternal(instanceId));
    }

    public synchronized Optional<PackageInstance> resolveStack(UUID owner, UUID instanceId, UUID stackId,
                                                                boolean delivered, MinecraftServer server, String operator) {
        PackageInstance instance = find(owner, instanceId).orElse(null);
        PackageDeliveryBatch batch = deliveryBatches.get(instanceId);
        if (instance == null || batch == null || instance.status() != PackageInstance.Status.BLOCKED) return Optional.empty();
        PackageDeliveryBatch.StackEntry entry = batch.stacks().stream().filter(stack -> stack.stackId().equals(stackId)).findFirst().orElse(null);
        if (entry == null || entry.status() != PackageDeliveryBatch.StackStatus.BLOCKED) return Optional.empty();
        PackageDeliveryBatch updated = batch.withProgress(stackId, delivered ? entry.quantity() : 0L,
                delivered ? PackageDeliveryBatch.StackStatus.DELIVERED : PackageDeliveryBatch.StackStatus.PENDING,
                System.currentTimeMillis()).withStatus(PackageDeliveryBatch.Status.WAITING_INBOX, System.currentTimeMillis());
        deliveryBatches.put(instanceId, updated);
        PackageInstance next = instance.withStatus(delivered ? PackageInstance.Status.WAITING_INBOX : PackageInstance.Status.WAITING_INBOX);
        instances.get(owner).put(instanceId, next);
        setDirty();
        PackageAuditLog.write(server, "resolve", "operator=" + operator + " owner=" + owner + " instance=" + instanceId
                + " stack=" + stackId + " decision=" + (delivered ? "delivered" : "pending"));
        return Optional.of(next);
    }

    public synchronized boolean cancel(UUID owner, UUID instanceId, MinecraftServer server, String operator) {
        PackageInstance instance = find(owner, instanceId).orElse(null);
        if (instance == null || instance.status() != PackageInstance.Status.BLOCKED) return false;
        PackageDeliveryBatch batch = deliveryBatches.get(instanceId);
        if (batch != null && batch.hasUncertainDelivery()) return false;
        boolean removed = remove(owner, instanceId);
        if (removed) PackageAuditLog.write(server, "cancel", "operator=" + operator + " owner=" + owner + " instance=" + instanceId);
        return removed;
    }

    /** Creates the initial transaction exactly once for an instance that has entered OPENING. */
    public synchronized PackageDeliveryBatch createDeliveryBatchIfAbsent(PackageDeliveryBatch candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Package delivery batch is required");
        }
        PackageDeliveryBatch existing = deliveryBatches.get(candidate.packageInstanceId());
        if (existing != null) {
            return existing;
        }
        deliveryBatches.put(candidate.packageInstanceId(), candidate);
        corruptBatchRecords.remove(candidate.batchId());
        setDirty();
        return candidate;
    }

    public synchronized boolean updateDeliveryBatch(PackageDeliveryBatch batch) {
        if (batch == null || !deliveryBatches.containsKey(batch.packageInstanceId())) {
            return false;
        }
        deliveryBatches.put(batch.packageInstanceId(), batch);
        setDirty();
        return true;
    }

    /**
     * Forces the current transaction state to disk before or after an irreversible inventory
     * mutation. Package openings deliver at most the player's inventory capacity in one action,
     * so this trades a bounded amount of I/O for a durable duplicate-prevention boundary.
     */
    public void flush(MinecraftServer server) {
        ServerLevel level = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (level == null) {
            throw new IllegalStateException("overworld unavailable");
        }
        level.getDataStorage().saveAndJoin();
    }

    public synchronized boolean update(PackageInstance instance) {
        var map = instances.get(instance.ownerId());
        if (map == null || !map.containsKey(instance.instanceId())) {
            return false;
        }
        map.put(instance.instanceId(), instance);
        setDirty();
        return true;
    }

    public synchronized boolean remove(UUID owner, UUID id) {
        var map = instances.get(owner);
        if (map == null || map.remove(id) == null) {
            return false;
        }
        var corrupt = corruptRecords.get(owner);
        if (corrupt != null) {
            corrupt.remove(id);
        }
        PackageDeliveryBatch batch = deliveryBatches.remove(id);
        if (batch != null) {
            corruptBatchRecords.remove(batch.batchId());
        }
        setDirty();
        return true;
    }

    private static PackageData fromTag(CompoundTag root) {
        PackageData data = new PackageData();
        HolderLookup.Provider registries = LOADING_REGISTRIES.get();
        CompoundTag owners = root.getCompoundOrEmpty("owners");
        for (String ownerKey : owners.keySet()) {
            CompoundTag values = owners.getCompoundOrEmpty(ownerKey);
            UUID owner;
            try {
                owner = UUID.fromString(ownerKey);
            } catch (IllegalArgumentException exception) {
                System.err.println("[omnitools] Retaining invalid package owner UUID: " + ownerKey);
                data.malformedOwnerRecords.put(ownerKey, values.copy());
                continue;
            }
            for (String idKey : values.keySet()) {
                CompoundTag raw = values.getCompoundOrEmpty(idKey).copy();
                UUID id;
                try {
                    id = UUID.fromString(idKey);
                } catch (IllegalArgumentException exception) {
                    System.err.println("[omnitools] Retaining invalid package instance UUID: " + idKey);
                    data.malformedInstanceRecords.computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
                            .put(idKey, raw);
                    continue;
                }
                try {
                    PackageInstance instance = decodeInstance(id, owner, raw, registries);
                    data.instances.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(id, instance);
                } catch (RuntimeException exception) {
                    String message = exception.getMessage() == null
                            ? exception.getClass().getSimpleName() : exception.getMessage();
                    System.err.println("[omnitools] Package instance " + id
                            + " could not be decoded and was quarantined as BLOCKED: " + message);
                    PackageInstance blocked = blockedInstance(id, owner, raw);
                    data.instances.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(id, blocked);
                    data.corruptRecords.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(id, raw);
                }
            }
        }
        data.decodeDeliveryBatches(root.getCompoundOrEmpty("delivery_batches"), registries);
        data.deliveryRecoveryPending = true;
        return data;
    }

    private synchronized void decodeDeliveryBatches(CompoundTag batches, HolderLookup.Provider registryLookup) {
        for (String batchKey : batches.keySet()) {
            CompoundTag raw = batches.getCompoundOrEmpty(batchKey).copy();
            UUID batchId;
            try {
                batchId = UUID.fromString(batchKey);
            } catch (IllegalArgumentException exception) {
                System.err.println("[omnitools] Retaining invalid package delivery batch UUID: " + batchKey);
                malformedBatchRecords.put(batchKey, raw);
                UUID instanceId = parseUuid(raw.getStringOr("package_instance_id", ""));
                if (instanceId != null) {
                    blockInstance(instanceId);
                }
                continue;
            }
            try {
                PackageDeliveryBatch batch = decodeDeliveryBatch(batchId, raw, registryLookup);
                if (findByInstanceIdInternal(batch.packageInstanceId()) == null) {
                    throw new IllegalArgumentException("delivery batch references a missing package instance");
                }
                if (deliveryBatches.putIfAbsent(batch.packageInstanceId(), batch) != null) {
                    throw new IllegalArgumentException("multiple delivery batches reference the same package instance");
                }
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                System.err.println("[omnitools] Package delivery batch " + batchId
                        + " could not be decoded and was quarantined: " + message);
                corruptBatchRecords.put(batchId, raw);
                UUID instanceId = parseUuid(raw.getStringOr("package_instance_id", ""));
                if (instanceId != null) {
                    blockInstance(instanceId);
                }
            }
        }
    }

    private static PackageInstance decodeInstance(UUID id, UUID owner, CompoundTag tag,
                                                   HolderLookup.Provider registries) {
        List<ItemStack> items = decodeItems(tag.getListOrEmpty("items"), registries);
        List<Long> quantities = new ArrayList<>();
        ListTag quantityTag = tag.getListOrEmpty("quantities");
        for (int index = 0; index < quantityTag.size(); index++) {
            if (!(quantityTag.get(index) instanceof LongTag value)) {
                throw new IllegalArgumentException("package quantity is not a long");
            }
            quantities.add(value.longValue());
        }
        return new PackageInstance(id, owner, tag.getStringOr("package_id", ""),
                tag.getIntOr("package_version", 1), tag.getStringOr("display", ""),
                decodeDescription(tag.getListOrEmpty("description")),
                tag.getStringOr("icon", ""), PackageDefinition.Mode.parse(tag.getStringOr("mode", "all")),
                items, quantities, tag.getStringOr("source", ""), tag.getStringOr("grant_key", ""),
                parseStatus(tag.getStringOr("status", "PENDING")), tag.getLongOr("granted_at", 0L),
                tag.getIntOr("selected", -1));
    }

    private static PackageDeliveryBatch decodeDeliveryBatch(UUID batchId, CompoundTag tag,
                                                             HolderLookup.Provider registryLookup) {
        UUID instanceId = UUID.fromString(tag.getStringOr("package_instance_id", ""));
        ListTag entries = tag.getListOrEmpty("stacks");
        List<PackageDeliveryBatch.StackEntry> stacks = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            if (!(entries.get(index) instanceof CompoundTag stackTag)) {
                throw new IllegalArgumentException("delivery batch stack is not a compound");
            }
            UUID stackId = UUID.fromString(stackTag.getStringOr("stack_id", ""));
            ItemStack snapshot = decodeItem(stackTag.getCompoundOrEmpty("item"), registryLookup);
            boolean logical = stackTag.contains("total_quantity");
            long quantity = logical ? stackTag.getLongOr("total_quantity", 0L) : stackTag.getIntOr("quantity", 0);
            PackageDeliveryBatch.StackStatus stackStatus = parseDeliveryStackStatus(
                    stackTag.getStringOr("status", "PENDING"));
            long deliveredQuantity = logical ? stackTag.getLongOr("delivered_quantity", 0L)
                    : (stackStatus == PackageDeliveryBatch.StackStatus.DELIVERED ? quantity : 0L);
            stacks.add(new PackageDeliveryBatch.StackEntry(stackId, snapshot, quantity, deliveredQuantity, stackStatus));
        }
        return new PackageDeliveryBatch(batchId, instanceId, stacks, tag.getIntOr("cursor", 0),
                parseDeliveryStatus(tag.getStringOr("status", "PENDING")),
                tag.getLongOr("created_at", 0L), tag.getLongOr("updated_at", 0L));
    }

    private static PackageInstance blockedInstance(UUID id, UUID owner, CompoundTag tag) {
        return new PackageInstance(id, owner, tag.getStringOr("package_id", "corrupt"),
                tag.getIntOr("package_version", 1), tag.getStringOr("display", "损坏礼包"),
                decodeDescription(tag.getListOrEmpty("description")),
                tag.getStringOr("icon", "minecraft:barrier"), PackageDefinition.Mode.ALL,
                List.of(), List.of(), tag.getStringOr("source", ""), tag.getStringOr("grant_key", ""),
                PackageInstance.Status.BLOCKED, tag.getLongOr("granted_at", 0L), -1);
    }

    private static PackageInstance.Status parseStatus(String value) {
        try {
            return PackageInstance.Status.valueOf(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("unknown package status: " + value, exception);
        }
    }

    private static List<ItemStack> decodeItems(ListTag list, HolderLookup.Provider registries) {
        List<ItemStack> result = new ArrayList<>();
        RegistryOps<Tag> ops = registries == null ? null : RegistryOps.create(NbtOps.INSTANCE, registries);
        for (int index = 0; index < list.size(); index++) {
            if (!(list.get(index) instanceof CompoundTag tag)) {
                throw new IllegalArgumentException("package item snapshot is not a compound");
            }
            ItemStack stack = (ops == null
                    ? ItemStack.CODEC.parse(NbtOps.INSTANCE, tag)
                    : ItemStack.CODEC.parse(ops, tag))
                    .getOrThrow(message -> new IllegalArgumentException(
                            "package item snapshot is invalid: " + message));
            result.add(stack);
        }
        return List.copyOf(result);
    }

    private static List<String> decodeDescription(ListTag list) {
        List<String> description = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            if (!(list.get(index) instanceof StringTag line)) {
                throw new IllegalArgumentException("package description entry is not a string");
            }
            description.add(line.value());
        }
        return List.copyOf(description);
    }

    private static ItemStack decodeItem(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries == null ? null : RegistryOps.create(NbtOps.INSTANCE, registries);
        return (ops == null ? ItemStack.CODEC.parse(NbtOps.INSTANCE, tag) : ItemStack.CODEC.parse(ops, tag))
                .getOrThrow(message -> new IllegalArgumentException("package item snapshot is invalid: " + message));
    }

    private static PackageDeliveryBatch.Status parseDeliveryStatus(String value) {
        try {
            return PackageDeliveryBatch.Status.valueOf(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("unknown package delivery status: " + value, exception);
        }
    }

    private static PackageDeliveryBatch.StackStatus parseDeliveryStackStatus(String value) {
        try {
            return PackageDeliveryBatch.StackStatus.valueOf(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("unknown package delivery stack status: " + value, exception);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private PackageInstance findByInstanceIdInternal(UUID instanceId) {
        for (LinkedHashMap<UUID, PackageInstance> ownerInstances : instances.values()) {
            PackageInstance instance = ownerInstances.get(instanceId);
            if (instance != null) {
                return instance;
            }
        }
        return null;
    }

    private void blockInstance(UUID instanceId) {
        PackageInstance instance = findByInstanceIdInternal(instanceId);
        if (instance == null || instance.status() == PackageInstance.Status.BLOCKED) {
            return;
        }
        instances.get(instance.ownerId()).put(instanceId, instance.withStatus(PackageInstance.Status.BLOCKED));
    }

    /** Resolves only provable transaction states after a SavedData reload. */
    private synchronized void reconcileDeliveryBatches() {
        if (!deliveryRecoveryPending) {
            return;
        }
        deliveryRecoveryPending = false;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (PackageDeliveryBatch batch : List.copyOf(deliveryBatches.values())) {
            PackageInstance instance = findByInstanceIdInternal(batch.packageInstanceId());
            if (instance == null) {
                corruptBatchRecords.put(batch.batchId(), encodeDeliveryBatch(batch, registries));
                deliveryBatches.remove(batch.packageInstanceId());
                changed = true;
                continue;
            }
            if (batch.hasUncertainDelivery() || batch.hasBlockedStack() || batch.status() == PackageDeliveryBatch.Status.BLOCKED) {
                deliveryBatches.put(batch.packageInstanceId(), batch.withStatus(PackageDeliveryBatch.Status.BLOCKED, now));
                blockInstance(batch.packageInstanceId());
                changed = true;
                continue;
            }
            if (batch.isComplete()) {
                deliveryBatches.put(batch.packageInstanceId(), batch.withStatus(PackageDeliveryBatch.Status.COMPLETED, now));
                if (instance.status() != PackageInstance.Status.OPENED) {
                    instances.get(instance.ownerId()).put(instance.instanceId(),
                            instance.withStatus(PackageInstance.Status.OPENED));
                }
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    private static CompoundTag toTag(PackageData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag owners = new CompoundTag();
        HolderLookup.Provider registries = data.registries;
        for (var ownerEntry : data.instances.entrySet()) {
            CompoundTag values = new CompoundTag();
            for (var entry : ownerEntry.getValue().entrySet()) {
                CompoundTag corrupt = data.corruptRecords.getOrDefault(ownerEntry.getKey(), new LinkedHashMap<>())
                        .get(entry.getKey());
                if (corrupt != null) {
                    CompoundTag blocked = corrupt.copy();
                    blocked.putString("status", PackageInstance.Status.BLOCKED.name());
                    values.put(entry.getKey().toString(), blocked);
                    continue;
                }
                PackageInstance instance = entry.getValue();
                CompoundTag tag = new CompoundTag();
                tag.putString("package_id", instance.packageId());
                tag.putInt("package_version", instance.packageVersion());
                tag.putString("display", instance.displayName());
                if (!instance.description().isEmpty()) {
                    ListTag description = new ListTag();
                    for (String line : instance.description()) {
                        description.add(StringTag.valueOf(line));
                    }
                    tag.put("description", description);
                }
                tag.putString("icon", instance.iconId());
                tag.putString("mode", instance.mode().serializedName());
                tag.putString("source", instance.sourceEvent());
                if (!instance.grantKey().isBlank()) {
                    tag.putString("grant_key", instance.grantKey());
                }
                tag.putString("status", instance.status().name());
                tag.putLong("granted_at", instance.grantedAt());
                tag.putInt("selected", instance.selectedItemIndex());
                ListTag items = new ListTag();
                for (ItemStack stack : instance.items()) {
                    Tag encoded = (registries == null
                            ? ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack)
                            : ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), stack))
                            .getOrThrow(message -> new IllegalStateException(
                                    "package item snapshot cannot be encoded: " + message));
                    if (!(encoded instanceof CompoundTag compound)) {
                        throw new IllegalStateException("package item snapshot did not encode as a compound");
                    }
                    items.add(compound);
                }
                tag.put("items", items);
                ListTag quantities = new ListTag();
                for (long quantity : instance.quantities()) {
                    quantities.add(LongTag.valueOf(quantity));
                }
                tag.put("quantities", quantities);
                values.put(entry.getKey().toString(), tag);
            }
            owners.put(ownerEntry.getKey().toString(), values);
        }
        for (Map.Entry<UUID, LinkedHashMap<String, CompoundTag>> ownerEntry : data.malformedInstanceRecords.entrySet()) {
            String ownerKey = ownerEntry.getKey().toString();
            CompoundTag values = owners.getCompoundOrEmpty(ownerKey).copy();
            for (Map.Entry<String, CompoundTag> entry : ownerEntry.getValue().entrySet()) {
                if (!values.contains(entry.getKey())) {
                    values.put(entry.getKey(), entry.getValue().copy());
                }
            }
            owners.put(ownerKey, values);
        }
        for (Map.Entry<String, CompoundTag> entry : data.malformedOwnerRecords.entrySet()) {
            if (!owners.contains(entry.getKey())) {
                owners.put(entry.getKey(), entry.getValue().copy());
            }
        }
        root.put("owners", owners);
        CompoundTag batches = new CompoundTag();
        for (PackageDeliveryBatch batch : data.deliveryBatches.values()) {
            batches.put(batch.batchId().toString(), encodeDeliveryBatch(batch, data.registries));
        }
        for (Map.Entry<UUID, CompoundTag> entry : data.corruptBatchRecords.entrySet()) {
            if (!batches.contains(entry.getKey().toString())) {
                CompoundTag blocked = entry.getValue().copy();
                blocked.putString("status", PackageDeliveryBatch.Status.BLOCKED.name());
                batches.put(entry.getKey().toString(), blocked);
            }
        }
        for (Map.Entry<String, CompoundTag> entry : data.malformedBatchRecords.entrySet()) {
            if (!batches.contains(entry.getKey())) {
                batches.put(entry.getKey(), entry.getValue().copy());
            }
        }
        root.put("delivery_batches", batches);
        return root;
    }

    private static CompoundTag encodeDeliveryBatch(PackageDeliveryBatch batch, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("package_instance_id", batch.packageInstanceId().toString());
        tag.putInt("cursor", batch.cursor());
        tag.putString("status", batch.status().name());
        tag.putLong("created_at", batch.createdAt());
        tag.putLong("updated_at", batch.updatedAt());
        ListTag stacks = new ListTag();
        for (PackageDeliveryBatch.StackEntry entry : batch.stacks()) {
            CompoundTag stack = new CompoundTag();
            stack.putString("stack_id", entry.stackId().toString());
            stack.put("item", encodeItem(entry.itemSnapshot(), registries));
            stack.putLong("total_quantity", entry.quantity());
            stack.putLong("delivered_quantity", entry.deliveredQuantity());
            stack.putInt("quantity", (int) Math.min(Integer.MAX_VALUE, entry.quantity()));
            stack.putString("status", entry.status().name());
            stacks.add(stack);
        }
        tag.put("stacks", stacks);
        return tag;
    }

    private static CompoundTag encodeItem(ItemStack stack, HolderLookup.Provider registries) {
        Tag encoded = (registries == null
                ? ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack)
                : ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), stack))
                .getOrThrow(message -> new IllegalStateException(
                        "package item snapshot cannot be encoded: " + message));
        if (!(encoded instanceof CompoundTag compound)) {
            throw new IllegalStateException("package item snapshot did not encode as a compound");
        }
        return compound;
    }
}
