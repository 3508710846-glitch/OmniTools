package dev.modmind.omnitools;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.OperationalErrorReporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** World-persistent, player-owned item storage used by the cloud storage menus. */
public final class CloudStorageData extends SavedData {
    public static final int SLOTS_PER_PAGE = 45;
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_cloud_storage";
    private static final String LEGACY_DATA_ID = "qiandao_cloud_storage";
    private static final String PLAYERS_KEY = "players";
    private static final String UNLOCKED_PAGES_KEY = "unlocked_pages";
    private static final String PAGES_KEY = "pages";
    private static final String EXPANSIONS_KEY = "expansions";
    private static final int MAX_EXPANSION_OPERATIONS = 512;
    static final int MAX_ITEM_BYTES = 32 * 1024;
    private static final ThreadLocal<HolderLookup.Provider> LOADING_REGISTRIES = new ThreadLocal<>();

    public static final SavedDataType<CloudStorageData> TYPE = new SavedDataType<>(
            DATA_ID,
            CloudStorageData::new,
            CompoundTag.CODEC.xmap(CloudStorageData::fromTag, CloudStorageData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private static final SavedDataType<CloudStorageData> LEGACY_TYPE = new SavedDataType<>(
            LEGACY_DATA_ID,
            CloudStorageData::new,
            CompoundTag.CODEC.xmap(CloudStorageData::fromTag, CloudStorageData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    static SavedDataType<CloudStorageData> legacyType() {
        return LEGACY_TYPE;
    }

    private final Map<UUID, StorageRecord> players = new HashMap<>();
    /** Wallet-linked page-unlock intents. PREPARED entries make a debit recoverable after a stop. */
    private final Map<UUID, ExpansionOperation> expansions = new HashMap<>();
    /** Retains a corrupt player record byte-for-byte so later saves cannot silently erase it. */
    private final Map<String, CompoundTag> malformedRecords = new HashMap<>();
    private HolderLookup.Provider registries;

    public static CloudStorageData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while loading cloud storage data");
        }
        LOADING_REGISTRIES.set(server.registryAccess());
        try {
            CloudStorageData data = overworld.getDataStorage().computeIfAbsent(TYPE);
            data.registries = server.registryAccess();
            return data;
        } finally {
            LOADING_REGISTRIES.remove();
        }
    }

    public static CloudStorageData get(ServerPlayer player) {
        return get(player.level().getServer());
    }

    /** Forces cloud-storage pages and expansion evidence to disk at an irreversible wallet boundary. */
    public void flush(MinecraftServer server) {
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while saving cloud storage data");
        }
        overworld.getDataStorage().saveAndJoin();
    }

    public synchronized int unlockedPages(UUID playerId) {
        StorageRecord record = players.get(playerId);
        return record == null ? CloudStorageConfig.MIN_PAGES : record.unlockedPages;
    }

    public synchronized boolean isQuarantined(UUID playerId) {
        return playerId != null && malformedRecords.containsKey(playerId.toString());
    }

    public synchronized List<ItemStack> page(UUID playerId, int page) {
        StorageRecord record = players.get(playerId);
        if (record == null || page < 0 || page >= record.unlockedPages) {
            return emptyPage();
        }
        return copyPage(record.pages.get(page));
    }

    public synchronized void savePage(UUID playerId, int page, List<ItemStack> items) {
        if (page < 0 || page >= CloudStorageConfig.MAX_PAGES) {
            throw new IllegalArgumentException("Cloud storage page is out of range");
        }
        if (items.size() != SLOTS_PER_PAGE) {
            throw new IllegalArgumentException("Cloud storage page has an unexpected slot count");
        }

        rejectQuarantinedRecord(playerId);
        StorageRecord record = players.computeIfAbsent(playerId, ignored -> new StorageRecord());
        if (page >= record.unlockedPages) {
            throw new IllegalStateException("Cannot write to a locked cloud storage page");
        }
        List<ItemStack> validated = validatePage(items, registries);
        if (ItemStack.listMatches(record.pages.get(page), validated)) {
            return;
        }
        record.pages.set(page, validated);
        setDirty();
    }

    /**
     * Persists a full validated page through a durable journal before the menu reports the move as
     * successful. Once the page write may have reached disk, recovery keeps the operation evidence
     * instead of guessing which player-inventory state survived a crash.
     */
    public CommitResult commitPage(MinecraftServer server, UUID playerId, int page, List<ItemStack> items) {
        return commitPage(server, playerId, page, items, null, "direct");
    }

    /** Commits a full session checkpoint with traceable session metadata. */
    public CommitResult commitPage(MinecraftServer server, UUID playerId, int page, List<ItemStack> items,
                                   UUID sessionId, String checkpointReason) {
        return commitPage(server, playerId, page, items, sessionId, checkpointReason, null);
    }

    /** Session overload retains the page image observed when the menu was opened for audit. */
    public CommitResult commitPage(MinecraftServer server, UUID playerId, int page, List<ItemStack> items,
                                   UUID sessionId, String checkpointReason, List<ItemStack> openedPageSnapshot) {
        if (server == null || playerId == null) {
            return CommitResult.rejected("server and player id are required");
        }
        List<ItemStack> validated;
        List<ItemStack> before;
        try {
            validated = validatePage(items, registries);
            before = page(playerId, page);
        } catch (RuntimeException exception) {
            reportCommitFailure(playerId, page, null, "REJECTED", "reject_before_mutation", exception);
            return CommitResult.rejected(describe(exception));
        }
        if (ItemStack.listMatches(before, validated)) {
            return CommitResult.unchanged();
        }

        CloudStorageJournalData journal;
        CloudStorageJournalData.Entry entry;
        try {
            journal = CloudStorageJournalData.get(server);
            if (journal.hasUnresolvedOperation(playerId, page)) {
                reportCommitFailure(playerId, page, null, "RECOVERY_REQUIRED", "manual_journal_recovery_required",
                        new IllegalStateException("An earlier cloud storage operation is awaiting recovery"));
                return CommitResult.recoveryRequired("an earlier operation is awaiting recovery");
            }
            entry = journal.prepare(playerId, page, CloudStorageJournalData.operationFor(before, validated),
                    before, validated, System.currentTimeMillis(), sessionId, checkpointReason,
                    openedPageSnapshot == null ? before : openedPageSnapshot);
        } catch (RuntimeException exception) {
            reportCommitFailure(playerId, page, null, "REJECTED", "journal_write_failed_before_mutation", exception);
            return CommitResult.rejected(describe(exception));
        }

        // Once PREPARED has been created, do not turn a flush failure into a harmless retry.  The
        // record carries the full before/after evidence and may yet be written by normal SavedData
        // shutdown; freeze the session and let startup/admin recovery decide from the page image.
        try {
            journal.flush(server);
        } catch (RuntimeException exception) {
            reportCommitFailure(playerId, page, entry.operationId(), "RECOVERY_PENDING",
                    "prepared_journal_retained_for_recovery", exception);
            return CommitResult.recoveryPending(entry.operationId(), describe(exception));
        }

        try {
            replacePage(playerId, page, validated);
            journal.flush(server);
        } catch (RuntimeException exception) {
            reportCommitFailure(playerId, page, entry.operationId(), "RECOVERY_PENDING",
                    "journal_retained_for_startup_recovery", exception);
            return CommitResult.recoveryPending(entry.operationId(), describe(exception));
        }

        try {
            journal.transition(entry.operationId(), CloudStorageJournalData.Status.COMMITTED, "page persisted");
            journal.flush(server);
            return CommitResult.committed(entry.operationId());
        } catch (RuntimeException exception) {
            reportCommitFailure(playerId, page, entry.operationId(), "RECOVERY_PENDING",
                    "persisted_page_retained_for_startup_recovery", exception);
            return CommitResult.recoveryPending(entry.operationId(), describe(exception));
        }
    }

    private static void reportCommitFailure(UUID playerId, int page, UUID operationId, String state,
                                            String recoveryAction, RuntimeException exception) {
        OperationalErrorReporter.global().warn(OperationalErrorReporter.Context
                        .forModule(ModuleId.CLOUD_STORAGE, "commit_page")
                        .withPlayer(playerId)
                        .withOperation(operationId)
                        .withState(state)
                        .withParameters(Map.of("page", Integer.toString(page)))
                        .withRecoveryAction(recoveryAction),
                exception);
    }

    synchronized void replacePage(UUID playerId, int page, List<ItemStack> items) {
        if (page < 0 || page >= CloudStorageConfig.MAX_PAGES || items == null || items.size() != SLOTS_PER_PAGE) {
            throw new IllegalArgumentException("Cloud storage page is out of range");
        }
        rejectQuarantinedRecord(playerId);
        StorageRecord record = players.computeIfAbsent(playerId, ignored -> new StorageRecord());
        if (page >= record.unlockedPages) {
            throw new IllegalStateException("Cannot write to a locked cloud storage page");
        }
        List<ItemStack> validated = validatePage(items, registries);
        if (!ItemStack.listMatches(record.pages.get(page), validated)) {
            record.pages.set(page, validated);
            setDirty();
        }
    }

    static List<ItemStack> validatePage(List<ItemStack> items, HolderLookup.Provider registries) {
        if (items == null || items.size() != SLOTS_PER_PAGE) {
            throw new IllegalArgumentException("Cloud storage page has an unexpected slot count");
        }
        List<ItemStack> copy = new ArrayList<>(SLOTS_PER_PAGE);
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                copy.add(ItemStack.EMPTY);
                continue;
            }
            Tag encoded = encodeStack(stack, registries);
            if (!(encoded instanceof CompoundTag itemTag) || itemTag.sizeInBytes() > MAX_ITEM_BYTES) {
                throw new IllegalArgumentException("Cloud storage item cannot be serialized safely");
            }
            ItemStack decoded = decodeStack(itemTag, registries);
            if (decoded.isEmpty() || decoded.getCount() != stack.getCount()
                    || !ItemStack.isSameItemSameComponents(stack, decoded)) {
                throw new IllegalArgumentException("Cloud storage item changed after serialization");
            }
            copy.add(stack.copy());
        }
        return List.copyOf(copy);
    }

    /**
     * Fast admission check for a vanilla Slot.  It deliberately uses the same codec and byte
     * limit as durable pages, so an item accepted into a session mirror cannot later be rejected
     * merely because it is not representable in cloud SavedData.
     */
    static boolean canStore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        List<ItemStack> candidate = emptyPage();
        candidate.set(0, stack);
        try {
            validatePage(candidate, null);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static CompoundTag encodePageSnapshot(List<ItemStack> items, HolderLookup.Provider registries) {
        List<ItemStack> validated = validatePage(items, registries);
        CompoundTag snapshot = new CompoundTag();
        for (int slot = 0; slot < SLOTS_PER_PAGE; slot++) {
            ItemStack stack = validated.get(slot);
            if (!stack.isEmpty()) {
                snapshot.put(Integer.toString(slot), encodeStack(stack, registries));
            }
        }
        return snapshot;
    }

    static List<ItemStack> decodePageSnapshot(CompoundTag snapshot, HolderLookup.Provider registries) {
        List<ItemStack> items = emptyPage();
        for (String key : snapshot.keySet()) {
            int slot;
            try {
                slot = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException("Cloud storage snapshot has a non-numeric slot key");
            }
            if (slot < 0 || slot >= SLOTS_PER_PAGE) {
                throw new IllegalArgumentException("Cloud storage snapshot has an out-of-range slot");
            }
            Tag rawItem = snapshot.get(key);
            if (!(rawItem instanceof CompoundTag itemTag)) {
                throw new IllegalArgumentException("Cloud storage snapshot item is not a compound");
            }
            items.set(slot, decodeStack(itemTag, registries));
        }
        return validatePage(items, registries);
    }

    public synchronized PageUnlockResult unlockNextPage(UUID playerId, int configuredMaximum) {
        int maximum = Math.max(CloudStorageConfig.MIN_PAGES,
                Math.min(CloudStorageConfig.MAX_PAGES, configuredMaximum));
        rejectQuarantinedRecord(playerId);
        StorageRecord record = players.computeIfAbsent(playerId, ignored -> new StorageRecord());
        if (record.unlockedPages >= maximum) {
            return new PageUnlockResult(false, record.unlockedPages);
        }

        record.unlockedPages++;
        record.ensurePageCount(record.unlockedPages);
        setDirty();
        return new PageUnlockResult(true, record.unlockedPages);
    }

    /** Creates a durable expansion intent without changing either balance or page access. */
    public synchronized ExpansionPreparation prepareNextPageUnlock(UUID playerId, int configuredMaximum) {
        if (playerId == null) return ExpansionPreparation.rejected("cloud storage player id is required");
        rejectQuarantinedRecord(playerId);
        ExpansionOperation pending = expansions.values().stream()
                .filter(operation -> operation.ownerId().equals(playerId) && operation.status() == ExpansionStatus.PREPARED)
                .findFirst().orElse(null);
        if (pending != null) return ExpansionPreparation.pending(pending);
        int maximum = Math.max(CloudStorageConfig.MIN_PAGES,
                Math.min(CloudStorageConfig.MAX_PAGES, configuredMaximum));
        StorageRecord record = players.computeIfAbsent(playerId, ignored -> new StorageRecord());
        if (record.unlockedPages >= maximum) return ExpansionPreparation.rejected("all cloud storage pages are already unlocked");
        ExpansionOperation operation = new ExpansionOperation(UUID.randomUUID(), playerId, record.unlockedPages,
                record.unlockedPages + 1, ExpansionStatus.PREPARED, System.currentTimeMillis(), System.currentTimeMillis(), "");
        expansions.put(operation.operationId(), operation);
        trimExpansionOperations();
        setDirty();
        return ExpansionPreparation.prepared(operation);
    }

    /** Completes an already durable expansion intent. Repeating a completed operation is harmless. */
    public synchronized PageUnlockResult commitPreparedPageUnlock(UUID playerId, UUID operationId) {
        ExpansionOperation operation = expansions.get(operationId);
        if (operation == null || playerId == null || !operation.ownerId().equals(playerId)) {
            return new PageUnlockResult(false, unlockedPages(playerId));
        }
        if (operation.status() == ExpansionStatus.COMMITTED) {
            return new PageUnlockResult(true, operation.targetUnlockedPages());
        }
        if (operation.status() != ExpansionStatus.PREPARED) {
            return new PageUnlockResult(false, unlockedPages(playerId));
        }
        StorageRecord record = players.computeIfAbsent(playerId, ignored -> new StorageRecord());
        if (record.unlockedPages == operation.beforeUnlockedPages()) {
            record.unlockedPages = operation.targetUnlockedPages();
            record.ensurePageCount(record.unlockedPages);
        } else if (record.unlockedPages != operation.targetUnlockedPages()) {
            return new PageUnlockResult(false, record.unlockedPages);
        }
        expansions.put(operationId, operation.withStatus(ExpansionStatus.COMMITTED, "page access persisted"));
        setDirty();
        return new PageUnlockResult(true, record.unlockedPages);
    }

    /** Rolls back an intent only before a durable wallet debit is observed. */
    public synchronized void rollbackPreparedPageUnlock(UUID playerId, UUID operationId, String reason) {
        ExpansionOperation operation = expansions.get(operationId);
        if (operation == null || playerId == null || !operation.ownerId().equals(playerId)
                || operation.status() != ExpansionStatus.PREPARED) return;
        expansions.put(operationId, operation.withStatus(ExpansionStatus.ROLLED_BACK, reason));
        setDirty();
    }

    /** Startup reconciliation uses the wallet marker as the source of truth for each prepared debit. */
    public synchronized ExpansionRecoveryReport reconcileExpansions(MinecraftServer server) {
        if (server == null) return new ExpansionRecoveryReport(0, 0, 0);
        int committed = 0;
        int rolledBack = 0;
        int unresolved = 0;
        for (ExpansionOperation operation : List.copyOf(expansions.values())) {
            if (operation.status() != ExpansionStatus.PREPARED) continue;
            if (CheckinData.get(server).hasCloudStorageExpansionCharge(operation.ownerId(), operation.operationId())) {
                PageUnlockResult result = commitPreparedPageUnlock(operation.ownerId(), operation.operationId());
                if (result.unlocked()) committed++; else unresolved++;
            } else {
                rollbackPreparedPageUnlock(operation.ownerId(), operation.operationId(), "startup wallet debit absent");
                rolledBack++;
            }
        }
        return new ExpansionRecoveryReport(committed, rolledBack, unresolved);
    }

    static CloudStorageData fromTag(CompoundTag root) {
        CloudStorageData data = new CloudStorageData();
        CompoundTag playerTags = root.getCompoundOrEmpty(PLAYERS_KEY);
        for (String key : playerTags.keySet()) {
            try {
                UUID playerId = UUID.fromString(key);
                CompoundTag recordTag = playerTags.getCompoundOrEmpty(key);
                int unlockedPages = clampPageCount(recordTag.getIntOr(UNLOCKED_PAGES_KEY,
                        CloudStorageConfig.MIN_PAGES));
                StorageRecord record = new StorageRecord(unlockedPages);
                CompoundTag pageTags = recordTag.getCompoundOrEmpty(PAGES_KEY);
                for (int page = 0; page < record.unlockedPages; page++) {
                    record.pages.set(page, decodePageSnapshot(pageTags.getCompoundOrEmpty(Integer.toString(page)),
                            LOADING_REGISTRIES.get()));
                }
                data.players.put(playerId, record);
            } catch (RuntimeException exception) {
                System.err.println("[omnitools] Retaining malformed cloud storage record " + key + ": "
                        + describe(exception));
                data.malformedRecords.put(key, playerTags.getCompoundOrEmpty(key).copy());
            }
        }
        CompoundTag expansionTags = root.getCompoundOrEmpty(EXPANSIONS_KEY);
        for (String key : expansionTags.keySet()) {
            try {
                UUID operationId = UUID.fromString(key);
                ExpansionOperation operation = ExpansionOperation.fromTag(operationId, expansionTags.getCompoundOrEmpty(key));
                data.expansions.put(operationId, operation);
            } catch (RuntimeException exception) {
                System.err.println("[omnitools] Retaining malformed cloud storage expansion evidence " + key + ": "
                        + describe(exception));
            }
        }
        return data;
    }

    static CompoundTag toTag(CloudStorageData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag playerTags = new CompoundTag();
        for (Map.Entry<UUID, StorageRecord> entry : data.players.entrySet()) {
            StorageRecord record = entry.getValue();
            CompoundTag recordTag = new CompoundTag();
            recordTag.putInt(UNLOCKED_PAGES_KEY, record.unlockedPages);
            CompoundTag pageTags = new CompoundTag();
            for (int page = 0; page < record.unlockedPages; page++) {
                pageTags.put(Integer.toString(page), encodePageSnapshot(record.pages.get(page), data.registries));
            }
            recordTag.put(PAGES_KEY, pageTags);
            playerTags.put(entry.getKey().toString(), recordTag);
        }
        for (Map.Entry<String, CompoundTag> entry : data.malformedRecords.entrySet()) {
            if (!playerTags.contains(entry.getKey())) {
                playerTags.put(entry.getKey(), entry.getValue().copy());
            }
        }
        root.put(PLAYERS_KEY, playerTags);
        CompoundTag expansionTags = new CompoundTag();
        for (Map.Entry<UUID, ExpansionOperation> entry : data.expansions.entrySet()) {
            expansionTags.put(entry.getKey().toString(), entry.getValue().toTag());
        }
        root.put(EXPANSIONS_KEY, expansionTags);
        return root;
    }

    private static Tag encodeStack(ItemStack stack, HolderLookup.Provider registries) {
        return (registries == null ? ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack)
                : ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), stack))
                .result().orElseThrow(() -> new IllegalArgumentException("Cloud storage item cannot be encoded"));
    }

    private static ItemStack decodeStack(CompoundTag tag, HolderLookup.Provider registries) {
        return (registries == null ? ItemStack.CODEC.parse(NbtOps.INSTANCE, tag)
                : ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag))
                .result().orElseThrow(() -> new IllegalArgumentException("Cloud storage item cannot be decoded"));
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private void rejectQuarantinedRecord(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Cloud storage player id is required");
        }
        if (malformedRecords.containsKey(playerId.toString())) {
            throw new IllegalStateException("Cloud storage record is quarantined for manual recovery");
        }
    }

    private void trimExpansionOperations() {
        if (expansions.size() <= MAX_EXPANSION_OPERATIONS) return;
        List<ExpansionOperation> terminal = expansions.values().stream()
                .filter(operation -> operation.status() != ExpansionStatus.PREPARED)
                .sorted(java.util.Comparator.comparingLong(ExpansionOperation::updatedAtMillis))
                .toList();
        int remove = expansions.size() - MAX_EXPANSION_OPERATIONS;
        for (ExpansionOperation operation : terminal) {
            if (remove-- <= 0) break;
            expansions.remove(operation.operationId());
        }
    }

    private static int clampPageCount(int value) {
        return Math.max(CloudStorageConfig.MIN_PAGES, Math.min(CloudStorageConfig.MAX_PAGES, value));
    }

    private static List<ItemStack> emptyPage() {
        List<ItemStack> items = new ArrayList<>(SLOTS_PER_PAGE);
        for (int slot = 0; slot < SLOTS_PER_PAGE; slot++) {
            items.add(ItemStack.EMPTY);
        }
        return items;
    }

    private static List<ItemStack> copyPage(List<ItemStack> source) {
        List<ItemStack> copy = new ArrayList<>(SLOTS_PER_PAGE);
        for (int slot = 0; slot < SLOTS_PER_PAGE; slot++) {
            ItemStack stack = source.get(slot);
            copy.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return copy;
    }

    public record PageUnlockResult(boolean unlocked, int unlockedPages) {
    }

    public enum ExpansionStatus { PREPARED, COMMITTED, ROLLED_BACK }

    public record ExpansionPreparation(ExpansionStatus status, ExpansionOperation operation, String reason) {
        static ExpansionPreparation prepared(ExpansionOperation operation) {
            return new ExpansionPreparation(ExpansionStatus.PREPARED, operation, "");
        }
        static ExpansionPreparation pending(ExpansionOperation operation) {
            return new ExpansionPreparation(ExpansionStatus.PREPARED, operation, "pending expansion recovery");
        }
        static ExpansionPreparation rejected(String reason) {
            return new ExpansionPreparation(null, null, reason == null ? "" : reason);
        }
        public boolean prepared() { return status == ExpansionStatus.PREPARED && operation != null && reason.isBlank(); }
    }

    public record ExpansionRecoveryReport(int committed, int rolledBack, int unresolved) {
    }

    public record ExpansionOperation(UUID operationId, UUID ownerId, int beforeUnlockedPages,
                                     int targetUnlockedPages, ExpansionStatus status, long createdAtMillis,
                                     long updatedAtMillis, String reason) {
        public ExpansionOperation {
            if (operationId == null || ownerId == null || beforeUnlockedPages < CloudStorageConfig.MIN_PAGES
                    || targetUnlockedPages != beforeUnlockedPages + 1 || targetUnlockedPages > CloudStorageConfig.MAX_PAGES
                    || status == null || createdAtMillis <= 0L || updatedAtMillis <= 0L) {
                throw new IllegalArgumentException("Cloud storage expansion operation is invalid");
            }
            reason = reason == null ? "" : reason.trim();
        }
        ExpansionOperation withStatus(ExpansionStatus next, String nextReason) {
            return new ExpansionOperation(operationId, ownerId, beforeUnlockedPages, targetUnlockedPages, next,
                    createdAtMillis, System.currentTimeMillis(), nextReason);
        }
        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("owner", ownerId.toString());
            tag.putInt("before_pages", beforeUnlockedPages);
            tag.putInt("target_pages", targetUnlockedPages);
            tag.putString("status", status.name());
            tag.putLong("created_at", createdAtMillis);
            tag.putLong("updated_at", updatedAtMillis);
            if (!reason.isBlank()) tag.putString("reason", reason);
            return tag;
        }
        static ExpansionOperation fromTag(UUID operationId, CompoundTag tag) {
            return new ExpansionOperation(operationId, UUID.fromString(tag.getStringOr("owner", "")),
                    tag.getIntOr("before_pages", 0), tag.getIntOr("target_pages", 0),
                    ExpansionStatus.valueOf(tag.getStringOr("status", "PREPARED")),
                    tag.getLongOr("created_at", 0L), tag.getLongOr("updated_at", 0L), tag.getStringOr("reason", ""));
        }
    }

    public record CommitResult(Status status, UUID operationId, String reason) {
        public boolean accepted() {
            return status == Status.UNCHANGED || status == Status.COMMITTED;
        }

        static CommitResult unchanged() {
            return new CommitResult(Status.UNCHANGED, null, "");
        }

        static CommitResult committed(UUID operationId) {
            return new CommitResult(Status.COMMITTED, operationId, "");
        }

        static CommitResult recoveryPending(UUID operationId, String reason) {
            return new CommitResult(Status.RECOVERY_PENDING, operationId, reason);
        }

        static CommitResult recoveryRequired(String reason) {
            return new CommitResult(Status.RECOVERY_REQUIRED, null, reason);
        }

        static CommitResult rejected(String reason) {
            return new CommitResult(Status.REJECTED, null, reason);
        }
    }

    public enum Status {
        UNCHANGED,
        COMMITTED,
        RECOVERY_PENDING,
        RECOVERY_REQUIRED,
        REJECTED
    }

    private static final class StorageRecord {
        private int unlockedPages;
        private final List<List<ItemStack>> pages = new ArrayList<>();

        private StorageRecord() {
            this(CloudStorageConfig.MIN_PAGES);
        }

        private StorageRecord(int unlockedPages) {
            this.unlockedPages = clampPageCount(unlockedPages);
            ensurePageCount(this.unlockedPages);
        }

        private void ensurePageCount(int count) {
            while (pages.size() < count) {
                pages.add(emptyPage());
            }
        }
    }
}
