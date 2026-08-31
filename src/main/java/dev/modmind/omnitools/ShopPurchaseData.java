package dev.modmind.omnitools;

import dev.modmind.omnitools.packages.PackageDefinition;
import dev.modmind.omnitools.packages.PackageInstance;
import dev.modmind.omnitools.packages.PackageSkillXp;
import dev.modmind.omnitools.packages.PackageSkillXpGrant;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable state machine for package purchases made through the shop. */
public final class ShopPurchaseData extends SavedData {
    private static final String ID = ModMindEntry.MOD_ID + "_shop_purchases";
    private static final ThreadLocal<HolderLookup.Provider> LOADING_REGISTRIES = new ThreadLocal<>();

    public static final SavedDataType<ShopPurchaseData> TYPE = new SavedDataType<>(ID, ShopPurchaseData::new,
            CompoundTag.CODEC.xmap(ShopPurchaseData::fromTag, ShopPurchaseData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, PurchaseTransaction> transactions = new LinkedHashMap<>();
    /** Retains malformed records for manual recovery instead of silently discarding financial history. */
    private final Map<String, CompoundTag> malformedTransactions = new LinkedHashMap<>();
    private HolderLookup.Provider registries;

    public static ShopPurchaseData get(MinecraftServer server) {
        ServerLevel level = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (level == null) {
            throw new IllegalStateException("overworld unavailable");
        }
        LOADING_REGISTRIES.set(server.registryAccess());
        try {
            ShopPurchaseData data = level.getDataStorage().computeIfAbsent(TYPE);
            data.registries = server.registryAccess();
            return data;
        } finally {
            LOADING_REGISTRIES.remove();
        }
    }

    public synchronized PurchaseTransaction createIfAbsent(PurchaseTransaction candidate) {
        PurchaseTransaction existing = transactions.get(candidate.transactionId());
        if (existing != null) {
            return existing;
        }
        transactions.put(candidate.transactionId(), candidate);
        malformedTransactions.remove(candidate.transactionId().toString());
        setDirty();
        return candidate;
    }

    public synchronized Optional<PurchaseTransaction> find(UUID transactionId) {
        return Optional.ofNullable(transactions.get(transactionId));
    }

    public synchronized List<PurchaseTransaction> list() {
        return List.copyOf(transactions.values());
    }

    /** Lets package history retention prove that a shop-created virtual asset reached completion. */
    public static boolean isCompletedGrantKey(MinecraftServer server, UUID ownerId, String grantKey) {
        if (server == null || ownerId == null || grantKey == null || !grantKey.startsWith("shop:")) {
            return false;
        }
        return get(server).list().stream().anyMatch(transaction -> ownerId.equals(transaction.ownerId())
                && transaction.status() == Status.COMPLETED && grantKey.equals(transaction.grantKey()));
    }

    public synchronized PurchaseTransaction transition(UUID transactionId, Status nextStatus, String auditReason) {
        PurchaseTransaction current = transactions.get(transactionId);
        if (current == null) {
            throw new IllegalArgumentException("unknown shop transaction: " + transactionId);
        }
        PurchaseTransaction next = current.withStatus(nextStatus, auditReason, System.currentTimeMillis());
        transactions.put(transactionId, next);
        setDirty();
        return next;
    }

    /** Forces purchase checkpoints and the matching currency marker to disk at an irreversible boundary. */
    public void flush(MinecraftServer server) {
        ServerLevel level = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (level == null) {
            throw new IllegalStateException("overworld unavailable");
        }
        level.getDataStorage().saveAndJoin();
    }

    private static ShopPurchaseData fromTag(CompoundTag root) {
        ShopPurchaseData data = new ShopPurchaseData();
        HolderLookup.Provider registries = LOADING_REGISTRIES.get();
        CompoundTag tags = root.getCompoundOrEmpty("transactions");
        for (String key : tags.keySet()) {
            CompoundTag raw = tags.getCompoundOrEmpty(key).copy();
            try {
                UUID transactionId = UUID.fromString(key);
                PurchaseTransaction transaction = decodeTransaction(transactionId, raw, registries);
                data.transactions.put(transactionId, transaction);
            } catch (RuntimeException exception) {
                System.err.println("[omnitools] Retaining malformed shop purchase " + key + ": "
                        + exception.getMessage());
                data.malformedTransactions.put(key, raw);
            }
        }
        return data;
    }

    private static CompoundTag toTag(ShopPurchaseData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag tags = new CompoundTag();
        for (PurchaseTransaction transaction : data.transactions.values()) {
            tags.put(transaction.transactionId().toString(), encodeTransaction(transaction, data.registries));
        }
        for (Map.Entry<String, CompoundTag> entry : data.malformedTransactions.entrySet()) {
            if (!tags.contains(entry.getKey())) {
                tags.put(entry.getKey(), entry.getValue().copy());
            }
        }
        root.put("transactions", tags);
        return root;
    }

    private static PurchaseTransaction decodeTransaction(UUID transactionId, CompoundTag tag,
                                                          HolderLookup.Provider registries) {
        UUID ownerId = UUID.fromString(tag.getStringOr("owner_id", ""));
        PackageInstance snapshot = decodePackageSnapshot(tag.getCompoundOrEmpty("package_snapshot"), registries);
        return new PurchaseTransaction(transactionId, ownerId, tag.getStringOr("owner_name", ""),
                tag.getIntOr("product_index", -1), tag.getLongOr("price", -1L),
                tag.getStringOr("reward_id", "package"), snapshot,
                Status.parse(tag.getStringOr("status", Status.PREPARED.name())),
                tag.getLongOr("created_at", 0L), tag.getLongOr("updated_at", 0L),
                tag.getStringOr("audit_reason", ""));
    }

    private static CompoundTag encodeTransaction(PurchaseTransaction transaction, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("owner_id", transaction.ownerId().toString());
        tag.putString("owner_name", transaction.ownerName());
        tag.putInt("product_index", transaction.productIndex());
        tag.putLong("price", transaction.price());
        tag.putString("reward_id", transaction.rewardId());
        tag.put("package_snapshot", encodePackageSnapshot(transaction.packageSnapshot(), registries));
        tag.putString("status", transaction.status().name());
        tag.putLong("created_at", transaction.createdAt());
        tag.putLong("updated_at", transaction.updatedAt());
        if (!transaction.auditReason().isBlank()) {
            tag.putString("audit_reason", transaction.auditReason());
        }
        return tag;
    }

    private static PackageInstance decodePackageSnapshot(CompoundTag tag, HolderLookup.Provider registries) {
        UUID instanceId = UUID.fromString(tag.getStringOr("instance_id", ""));
        UUID ownerId = UUID.fromString(tag.getStringOr("owner_id", ""));
        List<ItemStack> items = new ArrayList<>();
        for (Tag element : tag.getListOrEmpty("items")) {
            if (!(element instanceof CompoundTag itemTag)) {
                throw new IllegalArgumentException("package snapshot item is not a compound");
            }
            RegistryOps<Tag> ops = registries == null ? null : RegistryOps.create(NbtOps.INSTANCE, registries);
            ItemStack item = (ops == null ? ItemStack.CODEC.parse(NbtOps.INSTANCE, itemTag)
                    : ItemStack.CODEC.parse(ops, itemTag)).getOrThrow(message -> new IllegalArgumentException(
                    "package snapshot item is invalid: " + message));
            items.add(item);
        }
        List<Long> quantities = new ArrayList<>();
        for (Tag element : tag.getListOrEmpty("quantities")) {
            if (!(element instanceof LongTag value)) {
                throw new IllegalArgumentException("package snapshot quantity is not a long");
            }
            quantities.add(value.longValue());
        }
        List<String> description = new ArrayList<>();
        for (Tag element : tag.getListOrEmpty("description")) {
            if (!(element instanceof StringTag value)) {
                throw new IllegalArgumentException("package snapshot description is not a string");
            }
            description.add(value.value());
        }
        return new PackageInstance(instanceId, ownerId, tag.getStringOr("package_id", ""),
                tag.getIntOr("package_version", 1), tag.getStringOr("display", ""), description,
                tag.getStringOr("icon", ""), PackageDefinition.Mode.parse(tag.getStringOr("mode", "all")),
                items, quantities, decodeSkillXpGrants(tag.getListOrEmpty("skill_xp")), tag.getStringOr("source", ""), tag.getStringOr("grant_key", ""),
                PackageInstance.Status.PENDING, tag.getLongOr("granted_at", 0L), -1);
    }

    private static CompoundTag encodePackageSnapshot(PackageInstance snapshot, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("instance_id", snapshot.instanceId().toString());
        tag.putString("owner_id", snapshot.ownerId().toString());
        tag.putString("package_id", snapshot.packageId());
        tag.putInt("package_version", snapshot.packageVersion());
        tag.putString("display", snapshot.displayName());
        tag.putString("icon", snapshot.iconId());
        tag.putString("mode", snapshot.mode().serializedName());
        tag.putString("source", snapshot.sourceEvent());
        tag.putString("grant_key", snapshot.grantKey());
        tag.putLong("granted_at", snapshot.grantedAt());
        ListTag description = new ListTag();
        for (String line : snapshot.description()) {
            description.add(StringTag.valueOf(line));
        }
        tag.put("description", description);
        ListTag items = new ListTag();
        for (ItemStack item : snapshot.items()) {
            Tag encoded = (registries == null ? ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, item)
                    : ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), item))
                    .getOrThrow(message -> new IllegalStateException("package snapshot item cannot be encoded: " + message));
            if (!(encoded instanceof CompoundTag itemTag)) {
                throw new IllegalStateException("package snapshot item did not encode as a compound");
            }
            items.add(itemTag);
        }
        tag.put("items", items);
        ListTag quantities = new ListTag();
        for (long quantity : snapshot.quantities()) {
            quantities.add(LongTag.valueOf(quantity));
        }
        tag.put("quantities", quantities);
        tag.put("skill_xp", encodeSkillXpGrants(snapshot.skillXpGrants()));
        return tag;
    }

    private static List<PackageSkillXpGrant> decodeSkillXpGrants(ListTag entries) {
        List<PackageSkillXpGrant> grants = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            if (!(entries.get(index) instanceof CompoundTag entry)) {
                throw new IllegalArgumentException("package skill XP snapshot is not a compound");
            }
            List<PackageSkillXpGrant.TreeOption> options = new ArrayList<>();
            ListTag optionEntries = entry.getListOrEmpty("options");
            for (int optionIndex = 0; optionIndex < optionEntries.size(); optionIndex++) {
                if (!(optionEntries.get(optionIndex) instanceof CompoundTag option)) {
                    throw new IllegalArgumentException("package skill XP option is not a compound");
                }
                options.add(new PackageSkillXpGrant.TreeOption(option.getStringOr("tree", ""),
                        option.getStringOr("display", ""), option.getStringOr("icon", "")));
            }
            grants.add(new PackageSkillXpGrant(entry.getStringOr("id", ""), entry.getLongOr("amount", 0L),
                    PackageSkillXp.Mode.parse(entry.getStringOr("mode", "fixed")), options,
                    entry.getStringOr("resolved_tree", "")));
        }
        return List.copyOf(grants);
    }

    private static ListTag encodeSkillXpGrants(List<PackageSkillXpGrant> grants) {
        ListTag entries = new ListTag();
        for (PackageSkillXpGrant grant : grants) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", grant.id());
            entry.putLong("amount", grant.amount());
            entry.putString("mode", grant.mode().serializedName());
            if (!grant.resolvedTreeId().isBlank()) {
                entry.putString("resolved_tree", grant.resolvedTreeId());
            }
            ListTag options = new ListTag();
            for (PackageSkillXpGrant.TreeOption option : grant.options()) {
                CompoundTag optionEntry = new CompoundTag();
                optionEntry.putString("tree", option.treeId());
                optionEntry.putString("display", option.display());
                optionEntry.putString("icon", option.iconId());
                options.add(optionEntry);
            }
            entry.put("options", options);
            entries.add(entry);
        }
        return entries;
    }

    public record PurchaseTransaction(UUID transactionId, UUID ownerId, String ownerName, int productIndex,
                                      long price, String rewardId, PackageInstance packageSnapshot, Status status,
                                      long createdAt, long updatedAt, String auditReason) {
        public PurchaseTransaction {
            if (transactionId == null || ownerId == null || packageSnapshot == null) {
                throw new IllegalArgumentException("shop purchase identifiers and package snapshot are required");
            }
            if (productIndex < 0 || price < 0L) {
                throw new IllegalArgumentException("shop purchase product index and price must be non-negative");
            }
            ownerName = ownerName == null ? "" : ownerName.trim();
            rewardId = rewardId == null ? "" : rewardId.trim().toLowerCase(java.util.Locale.ROOT);
            if (!rewardId.matches("[a-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("invalid shop reward id");
            }
            if (!ownerId.equals(packageSnapshot.ownerId()) || !grantKey(transactionId, rewardId)
                    .equals(packageSnapshot.grantKey())) {
                throw new IllegalArgumentException("shop package snapshot does not match its transaction key");
            }
            if (packageSnapshot.status() != PackageInstance.Status.PENDING) {
                throw new IllegalArgumentException("shop package snapshot must be pending");
            }
            status = status == null ? Status.PREPARED : status;
            if (createdAt <= 0L || updatedAt <= 0L) {
                throw new IllegalArgumentException("shop purchase timestamps must be positive");
            }
            auditReason = auditReason == null ? "" : auditReason.trim();
            if (auditReason.length() > 1024) {
                throw new IllegalArgumentException("shop purchase audit reason is too long");
            }
        }

        public static PurchaseTransaction prepared(UUID transactionId, UUID ownerId, String ownerName,
                                                   int productIndex, long price, String rewardId,
                                                   PackageInstance packageSnapshot, long now) {
            return new PurchaseTransaction(transactionId, ownerId, ownerName, productIndex, price, rewardId,
                    packageSnapshot, Status.PREPARED, now, now, "");
        }

        public String grantKey() {
            return grantKey(transactionId, rewardId);
        }

        public PurchaseTransaction withStatus(Status next, String reason, long now) {
            if (!status.canTransitionTo(next)) {
                throw new IllegalStateException("invalid shop purchase transition: " + status + " -> " + next);
            }
            return new PurchaseTransaction(transactionId, ownerId, ownerName, productIndex, price, rewardId,
                    packageSnapshot, next, createdAt, now, reason);
        }

        private static String grantKey(UUID transactionId, String rewardId) {
            return "shop:" + transactionId + "#" + rewardId;
        }
    }

    public enum Status {
        PREPARED,
        CHARGED,
        PACKAGE_CREATED,
        COMPLETED,
        BLOCKED;

        public static Status parse(String value) {
            try {
                return valueOf(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("unknown shop purchase status: " + value, exception);
            }
        }

        boolean canTransitionTo(Status next) {
            if (this == next) {
                return true;
            }
            return switch (this) {
                case PREPARED -> next == CHARGED || next == BLOCKED;
                case CHARGED -> next == PACKAGE_CREATED || next == BLOCKED;
                case PACKAGE_CREATED -> next == COMPLETED || next == BLOCKED;
                case COMPLETED, BLOCKED -> false;
            };
        }
    }
}
