package dev.modmind.omnitools.packages;

import dev.modmind.omnitools.ModMindEntry;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import java.util.*;

/** World-persistent virtual package instances. */
public final class PackageData extends SavedData {
    private static final String ID = ModMindEntry.MOD_ID + "_packages";
    public static final SavedDataType<PackageData> TYPE = new SavedDataType<>(ID, PackageData::new,
            CompoundTag.CODEC.xmap(PackageData::fromTag, PackageData::toTag), DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private final Map<UUID, LinkedHashMap<UUID, PackageInstance>> instances = new HashMap<>();
    public static PackageData get(MinecraftServer server) { ServerLevel level = server.getLevel(Level.OVERWORLD); if (level == null) throw new IllegalStateException("overworld unavailable"); return level.getDataStorage().computeIfAbsent(TYPE); }
    public synchronized PackageInstance add(PackageInstance instance) { instances.computeIfAbsent(instance.ownerId(), k -> new LinkedHashMap<>()).put(instance.instanceId(), instance); setDirty(); return instance; }
    public synchronized Optional<PackageInstance> find(UUID owner, UUID id) { var map = instances.get(owner); return Optional.ofNullable(map == null ? null : map.get(id)); }
    public synchronized List<PackageInstance> list(UUID owner) { var map = instances.get(owner); return map == null ? List.of() : List.copyOf(map.values()); }
    public synchronized boolean update(PackageInstance instance) { var map = instances.get(instance.ownerId()); if (map == null || !map.containsKey(instance.instanceId())) return false; map.put(instance.instanceId(), instance); setDirty(); return true; }
    public synchronized boolean remove(UUID owner, UUID id) { var map = instances.get(owner); if (map == null || map.remove(id) == null) return false; setDirty(); return true; }
    private static PackageData fromTag(CompoundTag root) { PackageData data = new PackageData(); CompoundTag owners = root.getCompoundOrEmpty("owners"); for (String ownerKey : owners.keySet()) { UUID owner; try { owner = UUID.fromString(ownerKey); } catch (IllegalArgumentException e) { continue; } CompoundTag values = owners.getCompoundOrEmpty(ownerKey); for (String idKey : values.keySet()) { try { UUID id = UUID.fromString(idKey); CompoundTag t = values.getCompoundOrEmpty(idKey); List<ItemStack> items = decodeItems(t.getListOrEmpty("items")); List<Long> quantities = new ArrayList<>(); ListTag q = t.getListOrEmpty("quantities"); for (int i=0;i<q.size();i++) if (q.get(i) instanceof LongTag l) quantities.add(l.longValue()); PackageInstance instance = new PackageInstance(id, owner, t.getStringOr("package_id", ""), t.getIntOr("package_version", 1), t.getStringOr("display", ""), t.getStringOr("icon", ""), PackageDefinition.Mode.parse(t.getStringOr("mode", "all")), items, quantities, t.getStringOr("source", ""), parseStatus(t.getStringOr("status", "PENDING")), t.getLongOr("granted_at", 0L), t.getIntOr("selected", -1)); data.instances.computeIfAbsent(owner, k -> new LinkedHashMap<>()).put(id, instance); } catch (RuntimeException ignored) {} } } return data; }
    private static PackageInstance.Status parseStatus(String value) { try { return PackageInstance.Status.valueOf(value); } catch (RuntimeException e) { return PackageInstance.Status.PENDING; } }
    private static List<ItemStack> decodeItems(ListTag list) { List<ItemStack> result = new ArrayList<>(); for (int i=0;i<list.size();i++) if (list.get(i) instanceof CompoundTag tag) ItemStack.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().ifPresent(result::add); return result; }
    private static CompoundTag toTag(PackageData data) { CompoundTag root = new CompoundTag(); CompoundTag owners = new CompoundTag(); for (var ownerEntry : data.instances.entrySet()) { CompoundTag values = new CompoundTag(); for (var entry : ownerEntry.getValue().entrySet()) { PackageInstance p = entry.getValue(); CompoundTag t = new CompoundTag(); t.putString("package_id", p.packageId()); t.putInt("package_version", p.packageVersion()); t.putString("display", p.displayName()); t.putString("icon", p.iconId()); t.putString("mode", p.mode().serializedName()); t.putString("source", p.sourceEvent()); t.putString("status", p.status().name()); t.putLong("granted_at", p.grantedAt()); t.putInt("selected", p.selectedItemIndex()); ListTag items = new ListTag(); for (ItemStack stack : p.items()) ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack).result().filter(x -> x instanceof CompoundTag).ifPresent(x -> items.add(x)); t.put("items", items); ListTag quantities = new ListTag(); for (long q : p.quantities()) quantities.add(LongTag.valueOf(q)); t.put("quantities", quantities); values.put(entry.getKey().toString(), t); } owners.put(ownerEntry.getKey().toString(), values); } root.put("owners", owners); return root; }
}
