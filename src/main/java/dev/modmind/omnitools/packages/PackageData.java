package dev.modmind.omnitools.packages;

import dev.modmind.omnitools.ModMindEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
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
    private HolderLookup.Provider registries;

    public static PackageData get(MinecraftServer server) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            throw new IllegalStateException("overworld unavailable");
        }
        LOADING_REGISTRIES.set(server.registryAccess());
        try {
            PackageData data = level.getDataStorage().computeIfAbsent(TYPE);
            data.registries = server.registryAccess();
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
        setDirty();
        return true;
    }

    private static PackageData fromTag(CompoundTag root) {
        PackageData data = new PackageData();
        HolderLookup.Provider registries = LOADING_REGISTRIES.get();
        CompoundTag owners = root.getCompoundOrEmpty("owners");
        for (String ownerKey : owners.keySet()) {
            UUID owner;
            try {
                owner = UUID.fromString(ownerKey);
            } catch (IllegalArgumentException exception) {
                System.err.println("[omnitools] Ignoring invalid package owner UUID: " + ownerKey);
                continue;
            }
            CompoundTag values = owners.getCompoundOrEmpty(ownerKey);
            for (String idKey : values.keySet()) {
                UUID id;
                try {
                    id = UUID.fromString(idKey);
                } catch (IllegalArgumentException exception) {
                    System.err.println("[omnitools] Ignoring invalid package instance UUID: " + idKey);
                    continue;
                }
                CompoundTag raw = values.getCompoundOrEmpty(idKey).copy();
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
        return data;
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
                tag.getStringOr("icon", ""), PackageDefinition.Mode.parse(tag.getStringOr("mode", "all")),
                items, quantities, tag.getStringOr("source", ""), tag.getStringOr("grant_key", ""),
                parseStatus(tag.getStringOr("status", "PENDING")), tag.getLongOr("granted_at", 0L),
                tag.getIntOr("selected", -1));
    }

    private static PackageInstance blockedInstance(UUID id, UUID owner, CompoundTag tag) {
        return new PackageInstance(id, owner, tag.getStringOr("package_id", "corrupt"),
                tag.getIntOr("package_version", 1), tag.getStringOr("display", "损坏礼包"),
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
        root.put("owners", owners);
        return root;
    }
}
