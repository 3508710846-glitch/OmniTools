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
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.OperationalErrorReporter;

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

    /** Reads existing journal evidence for diagnostics without creating or dirtying SavedData. */
    public static Optional<CloudStorageJournalData> find(MinecraftServer server) {
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        return overworld == null ? Optional.empty() : Optional.ofNullable(overworld.getDataStorage().get(TYPE));
    }

    public synchronized Entry prepare(UUID ownerId, int page, Operation operation, List<ItemStack> before,
                                      List<ItemStack> after, long now) {
        return prepare(ownerId, page, operation, before, after, now, null, "direct");
    }

    /** Records session, snapshot hashes and changed slots alongside the durable before/after images. */
    public synchronized Entry prepare(UUID ownerId, int page, Operation operation, List<ItemStack> before,
                                      List<ItemStack> after, long now, UUID sessionId, String checkpointReason) {
        return prepare(ownerId, page, operation, before, after, now, sessionId, checkpointReason, before);
    }

    public synchronized Entry prepare(UUID ownerId, int page, Operation operation, List<ItemStack> before,
                                      List<ItemStack> after, long now, UUID sessionId, String checkpointReason,
                                      List<ItemStack> openedPageSnapshot) {
        if (ownerId == null || page < 0 || page >= CloudStorageConfig.MAX_PAGES || operation == null || now <= 0L) {
            throw new IllegalArgumentException("Cloud storage journal entry is invalid");
        }
        List<ItemStack> oldPage = CloudStorageData.validatePage(before, registries);
        List<ItemStack> newPage = CloudStorageData.validatePage(after, registries);
        List<ItemStack> openedPage = CloudStorageData.validatePage(openedPageSnapshot, registries);
        if (ItemStack.listMatches(oldPage, newPage)) {
            throw new IllegalArgumentException("Cloud storage journal cannot record an unchanged page");
        }
        Entry entry = new Entry(UUID.randomUUID(), ownerId, page, operation, Status.PREPARED, oldPage, newPage,
                now, now, "", Resolution.NONE, "", false,
                // The durable before image is the recovery source of truth for this checkpoint.
                // Keep the opening image separately so a later checkpoint does not describe the
                // whole session delta as though it were a single transaction.
                new SessionMetadata(sessionId, pageHash(openedPage), pageHash(oldPage), pageHash(newPage),
                        changedSlots(oldPage, newPage), changedSlots(openedPage, newPage), now, checkpointReason));
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

    /**
     * A prepared record means the player inventory and the storage page may have crossed a save
     * boundary at different times.  Do not accept another page mutation for that owner/page until
     * recovery has recorded a durable administrator decision; otherwise a later mutation would make the original
     * before/after snapshots impossible to audit.
     */
    public synchronized boolean hasUnresolvedOperation(UUID ownerId, int page) {
        if (ownerId == null || page < 0 || page >= CloudStorageConfig.MAX_PAGES) {
            return false;
        }
        return entries.values().stream().anyMatch(entry -> entry.ownerId().equals(ownerId)
                && entry.page() == page && (entry.status() == Status.PREPARED
                || entry.status() == Status.QUARANTINED && !entry.resolutionApplied()));
    }

    /** A player with ambiguous evidence cannot open another cloud mirror until recovery is explicit. */
    public synchronized boolean hasUnresolvedOperation(UUID ownerId) {
        if (ownerId == null) return false;
        return entries.values().stream().anyMatch(entry -> entry.ownerId().equals(ownerId)
                && (entry.status() == Status.PREPARED
                || entry.status() == Status.QUARANTINED && !entry.resolutionApplied()));
    }

    synchronized Entry transition(UUID operationId, Status status, String reason) {
        Entry current = entries.get(operationId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown cloud storage operation: " + operationId);
        }
        // Retries of a completed state transition must not change the recovery evidence.
        if (current.status() == status) {
            return current;
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
        RecoveryReport report = reconcile(storage);
        if (report.committed() > 0 || report.quarantined() > 0) {
            flush(server);
        }
        return report;
    }

    /** Reconciles journal entries against storage without requiring a live server flush. */
    synchronized RecoveryReport reconcile(CloudStorageData storage) {
        int committed = 0;
        int quarantined = 0;
        for (Entry entry : entries()) {
            try {
                List<ItemStack> current = storage.page(entry.ownerId(), entry.page());
                if (entry.status() == Status.QUARANTINED && entry.resolution() != Resolution.NONE
                        && !entry.resolutionApplied()) {
                    List<ItemStack> selected = entry.resolution() == Resolution.COMMIT ? entry.after() : entry.before();
                    if (ItemStack.listMatches(current, selected)) {
                        entries.put(entry.operationId(), entry.withResolutionApplied(System.currentTimeMillis()));
                        setDirty();
                    }
                    continue;
                }
                if (entry.status() != Status.PREPARED) {
                    continue;
                }
                if (ItemStack.listMatches(current, entry.after())) {
                    transition(entry.operationId(), Status.COMMITTED, "startup confirmed storage post-image");
                    committed++;
                } else {
                    transition(entry.operationId(), Status.QUARANTINED,
                            "startup could not prove both storage and player-inventory outcome");
                    quarantined++;
                }
            } catch (RuntimeException exception) {
                OperationalErrorReporter.global().warn(
                        OperationalErrorReporter.Context.forModule(ModuleId.CLOUD_STORAGE, "journal_reconcile")
                                .withPlayer(entry.ownerId())
                                .withOperation(entry.operationId())
                                .withState(entry.status().name())
                                .withParameters(Map.of("page", Integer.toString(entry.page())))
                                .withRecoveryAction("prepared_operation_quarantined"), exception);
                transition(entry.operationId(), Status.QUARANTINED,
                        "startup inspection failed: " + describe(exception));
                quarantined++;
            }
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
        if (resolution == null || resolution == Resolution.NONE) {
            return ResolutionResult.rejected("a commit or rollback resolution is required");
        }
        try {
            if (entry.status() == Status.PREPARED) {
                // Freeze the in-flight record as terminal evidence before a human chooses its page
                // outcome.  This prevents an interrupted manual action from becoming a later
                // automatic commit/rollback.
                entry = transition(operationId, Status.QUARANTINED, "manual recovery started");
                flush(server);
            }
            if (entry.resolution() != Resolution.NONE && entry.resolution() != resolution) {
                return ResolutionResult.rejected("operation was already resolved as "
                        + entry.resolution().name().toLowerCase());
            }
            if (entry.resolution() == Resolution.NONE) {
                // Persist the chosen target before mutating the storage page. If a crash happens
                // below, retrying the same operation can only replay this exact decision.
                entry = entry.withResolution(resolution, normalize(operator), System.currentTimeMillis());
                entries.put(operationId, entry);
                setDirty();
                flush(server);
            }
        } catch (RuntimeException exception) {
            return ResolutionResult.rejected(describe(exception));
        }
        List<ItemStack> current = storage.page(entry.ownerId(), entry.page());
        if (!ItemStack.listMatches(current, entry.before()) && !ItemStack.listMatches(current, entry.after())) {
            return ResolutionResult.rejected("storage page changed after the recorded operation");
        }
        List<ItemStack> selected = entry.resolution() == Resolution.COMMIT ? entry.after() : entry.before();
        try {
            storage.replacePage(entry.ownerId(), entry.page(), selected);
            flush(server);
            Entry applied = entry.withResolutionApplied(System.currentTimeMillis());
            entries.put(operationId, applied);
            setDirty();
            flush(server);
            return ResolutionResult.resolved(applied);
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
        if (entry.resolution() != Resolution.NONE) {
            tag.putString("resolution", entry.resolution().name());
            tag.putString("resolution_operator", entry.resolutionOperator());
            tag.putBoolean("resolution_applied", entry.resolutionApplied());
        }
        SessionMetadata metadata = entry.sessionMetadata();
        if (metadata.sessionId() != null) tag.putString("session_id", metadata.sessionId().toString());
        tag.putString("opened_page_hash", metadata.openedPageHash());
        tag.putString("before_page_hash", metadata.beforePageHash());
        tag.putString("target_page_hash", metadata.targetPageHash());
        tag.putLong("checkpoint_at", metadata.checkpointAt());
        tag.putString("checkpoint_reason", metadata.checkpointReason());
        net.minecraft.nbt.ListTag changed = new net.minecraft.nbt.ListTag();
        for (int slot : metadata.changedSlots()) changed.add(net.minecraft.nbt.IntTag.valueOf(slot));
        tag.put("changed_slots", changed);
        net.minecraft.nbt.ListTag sessionChanged = new net.minecraft.nbt.ListTag();
        for (int slot : metadata.sessionChangedSlots()) sessionChanged.add(net.minecraft.nbt.IntTag.valueOf(slot));
        tag.put("session_changed_slots", sessionChanged);
        tag.put("before", CloudStorageData.encodePageSnapshot(entry.before(), registries));
        tag.put("after", CloudStorageData.encodePageSnapshot(entry.after(), registries));
        return tag;
    }

    private static Entry decode(UUID id, CompoundTag tag, HolderLookup.Provider registries) {
        List<ItemStack> before = CloudStorageData.decodePageSnapshot(tag.getCompoundOrEmpty("before"), registries);
        List<ItemStack> after = CloudStorageData.decodePageSnapshot(tag.getCompoundOrEmpty("after"), registries);
        return new Entry(id, UUID.fromString(tag.getStringOr("owner", "")), tag.getIntOr("page", -1),
                Operation.parse(tag.getStringOr("operation", "")), Status.parse(tag.getStringOr("status", "")),
                before, after, tag.getLongOr("created_at", 0L), tag.getLongOr("updated_at", 0L),
                tag.getStringOr("reason", ""), Resolution.parse(tag.getStringOr("resolution", "NONE")),
                tag.getStringOr("resolution_operator", ""), tag.getBooleanOr("resolution_applied", false),
                SessionMetadata.fromTag(tag, before, after));
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

    private static String pageHash(List<ItemStack> page) {
        try {
            byte[] data = CloudStorageData.encodePageSnapshot(page, null).toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<Integer> changedSlots(List<ItemStack> before, List<ItemStack> after) {
        List<Integer> changed = new ArrayList<>();
        for (int slot = 0; slot < CloudStorageData.SLOTS_PER_PAGE; slot++) {
            if (!ItemStack.matches(before.get(slot), after.get(slot))) changed.add(slot);
        }
        return List.copyOf(changed);
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
                case COMMITTED, ROLLED_BACK, QUARANTINED -> false;
            };
        }
    }

    public enum Resolution {
        NONE,
        COMMIT,
        ROLLBACK;

        static Resolution parse(String value) {
            try {
                return valueOf(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("unknown cloud storage journal resolution: " + value, exception);
            }
        }
    }

    public record Entry(UUID operationId, UUID ownerId, int page, Operation operation, Status status,
                        List<ItemStack> before, List<ItemStack> after, long createdAt, long updatedAt, String reason,
                        Resolution resolution, String resolutionOperator, boolean resolutionApplied,
                        SessionMetadata sessionMetadata) {
        public Entry {
            if (operationId == null || ownerId == null || page < 0 || page >= CloudStorageConfig.MAX_PAGES
                    || operation == null || status == null || resolution == null || sessionMetadata == null
                    || createdAt <= 0L || updatedAt <= 0L) {
                throw new IllegalArgumentException("Cloud storage journal entry is invalid");
            }
            before = copyPage(before);
            after = copyPage(after);
            reason = reason == null ? "" : reason.trim();
            if (reason.length() > 1024) {
                throw new IllegalArgumentException("Cloud storage journal reason is too long");
            }
            resolutionOperator = resolutionOperator == null ? "" : resolutionOperator.trim();
            if (resolutionOperator.length() > 256) {
                throw new IllegalArgumentException("Cloud storage resolution operator is too long");
            }
            if (status != Status.QUARANTINED && resolution != Resolution.NONE) {
                throw new IllegalArgumentException("Only quarantined cloud storage operations may carry a resolution");
            }
            if (resolution == Resolution.NONE && resolutionApplied) {
                throw new IllegalArgumentException("Cloud storage resolution cannot be applied before it is selected");
            }
        }

        Entry withStatus(Status next, String nextReason, long now) {
            return new Entry(operationId, ownerId, page, operation, next, before, after, createdAt, now, nextReason,
                    Resolution.NONE, "", false, sessionMetadata);
        }

        Entry withResolution(Resolution nextResolution, String operator, long now) {
            if (status != Status.QUARANTINED || resolution != Resolution.NONE
                    || nextResolution == null || nextResolution == Resolution.NONE) {
                throw new IllegalStateException("Cloud storage operation cannot accept another resolution");
            }
            return new Entry(operationId, ownerId, page, operation, status, before, after, createdAt, now, reason,
                    nextResolution, operator, false, sessionMetadata);
        }

        Entry withResolutionApplied(long now) {
            if (status != Status.QUARANTINED || resolution == Resolution.NONE || resolutionApplied) {
                throw new IllegalStateException("Cloud storage resolution cannot be marked as applied");
            }
            return new Entry(operationId, ownerId, page, operation, status, before, after, createdAt, now, reason,
                    resolution, resolutionOperator, true, sessionMetadata);
        }
    }

    public record SessionMetadata(UUID sessionId, String openedPageHash, String beforePageHash, String targetPageHash,
                                  List<Integer> changedSlots, List<Integer> sessionChangedSlots,
                                  long checkpointAt, String checkpointReason) {
        public SessionMetadata {
            openedPageHash = normalizedHash(openedPageHash);
            beforePageHash = normalizedHash(beforePageHash);
            targetPageHash = normalizedHash(targetPageHash);
            changedSlots = validatedSlots(changedSlots);
            sessionChangedSlots = validatedSlots(sessionChangedSlots);
            checkpointAt = Math.max(0L, checkpointAt);
            checkpointReason = normalize(checkpointReason);
        }

        static SessionMetadata fromTag(CompoundTag tag, List<ItemStack> before, List<ItemStack> after) {
            UUID sessionId = null;
            String rawId = tag.getStringOr("session_id", "");
            if (!rawId.isBlank()) sessionId = UUID.fromString(rawId);
            List<Integer> legacyOrCheckpointChanged = slotsFromTag(tag, "changed_slots");
            List<Integer> sessionChanged = tag.contains("session_changed_slots")
                    ? slotsFromTag(tag, "session_changed_slots") : legacyOrCheckpointChanged;
            // Older records only stored the opening hash and session-wide changed slots.  The
            // before/after snapshots are authoritative, so derive the missing checkpoint fields
            // from them instead of preserving the historical ambiguity.
            return new SessionMetadata(sessionId, tag.getStringOr("opened_page_hash", ""),
                    tag.getStringOr("before_page_hash", pageHash(before)), tag.getStringOr("target_page_hash", ""),
                    tag.contains("before_page_hash") ? legacyOrCheckpointChanged
                            : CloudStorageJournalData.changedSlots(before, after),
                    sessionChanged, tag.getLongOr("checkpoint_at", 0L), tag.getStringOr("checkpoint_reason", "legacy"));
        }

        private static List<Integer> slotsFromTag(CompoundTag tag, String key) {
            List<Integer> slots = new ArrayList<>();
            for (int index = 0; index < tag.getListOrEmpty(key).size(); index++) {
                tag.getListOrEmpty(key).getInt(index).ifPresent(slots::add);
            }
            return slots;
        }

        private static List<Integer> validatedSlots(List<Integer> slots) {
            List<Integer> copy = List.copyOf(slots == null ? List.of() : slots);
            if (copy.stream().anyMatch(slot -> slot == null || slot < 0 || slot >= CloudStorageData.SLOTS_PER_PAGE)) {
                throw new IllegalArgumentException("Cloud storage changed slot is invalid");
            }
            if (copy.stream().distinct().count() != copy.size()) {
                throw new IllegalArgumentException("Cloud storage changed slot is duplicated");
            }
            return copy;
        }

        private static String normalizedHash(String hash) {
            String value = normalize(hash);
            if (!value.isEmpty() && !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Cloud storage page hash is invalid");
            }
            return value;
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
