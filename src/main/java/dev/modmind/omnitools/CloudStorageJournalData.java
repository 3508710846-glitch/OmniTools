package dev.modmind.omnitools;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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

/**
 * Durable evidence for cloud-storage page changes. Every entry retains the before and after page
 * snapshots so an administrator can recover a quarantined operation without guessing the item state.
 */
public final class CloudStorageJournalData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_cloud_storage_journal";
    private static final String ENTRIES_KEY = "entries";
    private static final ThreadLocal<HolderLookup.Provider> LOADING_REGISTRIES = new ThreadLocal<>();

    public static final SavedDataType<CloudStorageJournalData> TYPE = new SavedDataType<>(DATA_ID,
            CloudStorageJournalData::new,
            CompoundTag.CODEC.xmap(CloudStorageJournalData::fromTag, CloudStorageJournalData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    /** Preserve malformed evidence rather than replacing it with an empty journal on the next save. */
    private final Map<String, CompoundTag> malformedEntries = new LinkedHashMap<>();
    private HolderLookup.Provider registries;

    public static CloudStorageJournalData get(MinecraftServer server) {
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while loading cloud storage journal data");
        }
        LOADING_REGISTRIES.set(server.registryAccess());
        try {
            CloudStorageJournalData data = overworld.getDataStorage().computeIfAbsent(TYPE);
            data.registries = server.registryAccess();
            return data;
        } finally {
            LOADING_REGISTRIES.remove();
        }
    }

    public synchronized Entry prepare(UUID ownerId, int page, Operation operation, List<ItemStack> before,
                                      List<ItemStack> after, long now) {
        if (ownerId == null || page < 0 || page >= CloudStorageConfig.MAX_PAGES || operation == null || now <= 0L) {
            throw new IllegalArgumentException("Cloud storage journal entry is invalid");
        }
        List<ItemStack> oldPage = CloudStorageData.validatePage(before, registries);
        List<ItemStack> newPage = CloudStorageData.validatePage(after, registries);
        if (ItemStack.listMatches(oldPage, newPage)) {
            throw new IllegalArgumentException("Cloud storage journal cannot record an unchanged page");
        }
        Entry entry = new Entry(UUID.randomUUID(), ownerId, page, operation, Status.PREPARED, oldPage, newPage,
                now, now, "");
        entries.put(entry.operationId(), entry);
        setDirty();
        return entry;
    }

    public synchronized Optional<Entry> find(UUID operationId) {
        return Optional.ofNullable(entries.get(operationId));
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    synchronized Entry transition(UUID operationId, Status status, String reason) {
        Entry current = entries.get(operationId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown cloud storage operation: " + operationId);
        }
        if (!current.status().canTransitionTo(status)) {
            throw new IllegalStateException("Invalid cloud storage journal transition: " + current.status()
                    + " -> " + status);
        }
        Entry next = current.withStatus(status, reason, System.currentTimeMillis());
        entries.put(operationId, next);
        setDirty();
        return next;
    }

    /** Writes all current SavedData checkpoints before exposing an irreversible inventory change. */
    public void flush(MinecraftServer server) {
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while saving cloud storage data");
        }
        overworld.getDataStorage().saveAndJoin();
    }

    /**
     * Reconciles only when the storage page itself proves an outcome. A pre-image is intentionally
     * quarantined: the player inventory lives in a different persistence domain and may have been
     * saved before the server stopped.
     */
    public RecoveryReport reconcileStartup(MinecraftServer server, CloudStorageData storage) {
        int committed = 0;
        int quarantined = 0;
        for (Entry entry : entries()) {
            if (entry.status() != Status.PREPARED && entry.status() != Status.COMMITTED) {
                continue;
            }
            try {
                List<ItemStack> current = storage.page(entry.ownerId(), entry.page());
                if (entry.status() == Status.PREPARED && ItemStack.listMatches(current, entry.after())) {
                    transition(entry.operationId(), Status.COMMITTED, "startup confirmed storage post-image");
                    committed++;
                } else if (entry.status() == Status.COMMITTED && ItemStack.listMatches(current, entry.after())) {
                    // The durable page is already the committed outcome.
                } else {
                    transition(entry.operationId(), Status.QUARANTINED,
                            "startup could not prove both storage and player-inventory outcome");
                    quarantined++;
                }
            } catch (RuntimeException exception) {
                transition(entry.operationId(), Status.QUARANTINED,
                        "startup inspection failed: " + describe(exception));
                quarantined++;
            }
        }
        if (committed > 0 || quarantined > 0) {
            flush(server);
        }
        return new RecoveryReport(committed, quarantined);
    }

    /** Applies an administrator-selected page snapshot without attempting to modify a player's inventory. */
    public ResolutionResult resolve(MinecraftServer server, CloudStorageData storage, UUID operationId,
                                    Resolution resolution, String operator) {
        Entry entry = find(operationId).orElse(null);
        if (entry == null) {
            return ResolutionResult.rejected("operation not found");
        }
        if (entry.status() != Status.QUARANTINED && entry.status() != Status.PREPARED) {
            return ResolutionResult.rejected("operation is not awaiting recovery");
        }
        List<ItemStack> current = storage.page(entry.ownerId(), entry.page());
        if (!ItemStack.listMatches(current, entry.before()) && !ItemStack.listMatches(current, entry.after())) {
            return ResolutionResult.rejected("storage page changed after the recorded operation");
        }
        List<ItemStack> selected = resolution == Resolution.COMMIT ? entry.after() : entry.before();
        try {
            storage.replacePage(entry.ownerId(), entry.page(), selected);
            flush(server);
            Entry resolved = transition(operationId,
                    resolution == Resolution.COMMIT ? Status.COMMITTED : Status.ROLLED_BACK,
                    "administrator=" + normalize(operator) + ";resolution=" + resolution.name().toLowerCase());
            flush(server);
            return ResolutionResult.resolved(resolved);
        } catch (RuntimeException exception) {
            return ResolutionResult.rejected(describe(exception));
        }
    }

    static CloudStorageJournalData fromTag(CompoundTag root) {
        CloudStorageJournalData data = new CloudStorageJournalData();
        CompoundTag tags = root.getCompoundOrEmpty(ENTRIES_KEY);
        for (String key : tags.keySet()) {
            CompoundTag raw = tags.getCompoundOrEmpty(key).copy();
            try {
                UUID id = UUID.fromString(key);
                Entry entry = decode(id, raw, LOADING_REGISTRIES.get());
                data.entries.put(id, entry);
            } catch (RuntimeException exception) {
                System.err.println("[omnitools] Retaining malformed cloud storage journal entry " + key + ": "
                        + describe(exception));
                data.malformedEntries.put(key, raw);
            }
        }
        return data;
    }

    static CompoundTag toTag(CloudStorageJournalData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag tags = new CompoundTag();
        for (Entry entry : data.entries.values()) {
            tags.put(entry.operationId().toString(), encode(entry, data.registries));
        }
        for (Map.Entry<String, CompoundTag> malformed : data.malformedEntries.entrySet()) {
            if (!tags.contains(malformed.getKey())) {
                tags.put(malformed.getKey(), malformed.getValue().copy());
            }
        }
        root.put(ENTRIES_KEY, tags);
        return root;
    }

    private static CompoundTag encode(Entry entry, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("owner", entry.ownerId().toString());
        tag.putInt("page", entry.page());
        tag.putString("operation", entry.operation().name());
        tag.putString("status", entry.status().name());
        tag.putLong("created_at", entry.createdAt());
        tag.putLong("updated_at", entry.updatedAt());
        if (!entry.reason().isBlank()) {
            tag.putString("reason", entry.reason());
        }
        tag.put("before", CloudStorageData.encodePageSnapshot(entry.before(), registries));
        tag.put("after", CloudStorageData.encodePageSnapshot(entry.after(), registries));
        return tag;
    }

    private static Entry decode(UUID id, CompoundTag tag, HolderLookup.Provider registries) {
        return new Entry(id, UUID.fromString(tag.getStringOr("owner", "")), tag.getIntOr("page", -1),
                Operation.parse(tag.getStringOr("operation", "")), Status.parse(tag.getStringOr("status", "")),
                CloudStorageData.decodePageSnapshot(tag.getCompoundOrEmpty("before"), registries),
                CloudStorageData.decodePageSnapshot(tag.getCompoundOrEmpty("after"), registries),
                tag.getLongOr("created_at", 0L), tag.getLongOr("updated_at", 0L), tag.getStringOr("reason", ""));
    }

    static Operation operationFor(List<ItemStack> before, List<ItemStack> after) {
        long beforeCount = itemCount(before);
        long afterCount = itemCount(after);
        if (afterCount > beforeCount) {
            return Operation.DEPOSIT;
        }
        if (afterCount < beforeCount) {
            return Operation.WITHDRAW;
        }
        return Operation.MOVE;
    }

    private static long itemCount(List<ItemStack> page) {
        return page.stream().filter(stack -> stack != null && !stack.isEmpty()).mapToLong(ItemStack::getCount).sum();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public enum Operation {
        DEPOSIT,
        WITHDRAW,
        MOVE;

        static Operation parse(String value) {
            try {
                return valueOf(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("unknown cloud storage operation: " + value, exception);
            }
        }
    }

    public enum Status {
        PREPARED,
        COMMITTED,
        ROLLED_BACK,
        QUARANTINED;

        static Status parse(String value) {
            try {
                return valueOf(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("unknown cloud storage journal status: " + value, exception);
            }
        }

        boolean canTransitionTo(Status next) {
            if (this == next) {
                return true;
            }
            return switch (this) {
                case PREPARED -> next == COMMITTED || next == ROLLED_BACK || next == QUARANTINED;
                case QUARANTINED -> next == COMMITTED || next == ROLLED_BACK;
                case COMMITTED, ROLLED_BACK -> false;
            };
        }
    }

    public enum Resolution {
        COMMIT,
        ROLLBACK
    }

    public record Entry(UUID operationId, UUID ownerId, int page, Operation operation, Status status,
                        List<ItemStack> before, List<ItemStack> after, long createdAt, long updatedAt, String reason) {
        public Entry {
            if (operationId == null || ownerId == null || page < 0 || page >= CloudStorageConfig.MAX_PAGES
                    || operation == null || status == null || createdAt <= 0L || updatedAt <= 0L) {
                throw new IllegalArgumentException("Cloud storage journal entry is invalid");
            }
            before = copyPage(before);
            after = copyPage(after);
            reason = reason == null ? "" : reason.trim();
            if (reason.length() > 1024) {
                throw new IllegalArgumentException("Cloud storage journal reason is too long");
            }
        }

        Entry withStatus(Status next, String nextReason, long now) {
            return new Entry(operationId, ownerId, page, operation, next, before, after, createdAt, now, nextReason);
        }
    }

    public record RecoveryReport(int committed, int quarantined) {
    }

    public record ResolutionResult(boolean resolved, Entry entry, String reason) {
        static ResolutionResult resolved(Entry entry) {
            return new ResolutionResult(true, entry, "");
        }

        static ResolutionResult rejected(String reason) {
            return new ResolutionResult(false, null, reason);
        }
    }

    private static List<ItemStack> copyPage(List<ItemStack> source) {
        if (source == null || source.size() != CloudStorageData.SLOTS_PER_PAGE) {
            throw new IllegalArgumentException("Cloud storage journal page has an unexpected slot count");
        }
        List<ItemStack> copy = new ArrayList<>(CloudStorageData.SLOTS_PER_PAGE);
        for (ItemStack stack : source) {
            copy.add(stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return List.copyOf(copy);
    }
}
