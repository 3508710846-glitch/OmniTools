package dev.modmind.omnitools.skills;

import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.OperationalErrorReporter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable two-phase journal for skill XP mutations.  XP operation ids are not marked as
 * completed until the corresponding player snapshot has been written. A PREPARED entry retains
 * both images, so startup can deterministically complete a known transaction chain; terminal
 * states are idempotent.
 */
public final class SkillXpTransactionData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_skill_xp_transactions";
    private static final String ENTRIES_KEY = "entries";
    private static final int MAX_ENTRIES = 16_384;

    public static final SavedDataType<SkillXpTransactionData> TYPE = new SavedDataType<>(DATA_ID,
            SkillXpTransactionData::new,
            CompoundTag.CODEC.xmap(SkillXpTransactionData::fromTag, SkillXpTransactionData::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<OperationKey, String> blockingTransactions = new LinkedHashMap<>();
    private long nextSequence = 1L;

    public static SkillXpTransactionData get(MinecraftServer server) {
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is unavailable while loading skill XP transactions");
        }
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Creates and persists a PREPARED entry, or returns empty when the operation is already
     * prepared or committed. Rolled-back operations may be retried with the same operation id.
     */
    public synchronized Optional<Entry> prepare(UUID playerId, String operationId, String skillId,
                                                 SkillXpSource source, SkillTreeData.Progress before,
                                                 long now) {
        return prepare(playerId, operationId, skillId, source, before, before, now);
    }

    /** Records both snapshots before the corresponding skill progress mutation can be persisted. */
    public synchronized Optional<Entry> prepare(UUID playerId, String operationId, String skillId,
                                                 SkillXpSource source, SkillTreeData.Progress before,
                                                 SkillTreeData.Progress target, long now) {
        if (playerId == null || operationId == null || operationId.isBlank() || before == null || now <= 0L) {
            throw new IllegalArgumentException("Skill XP transaction is invalid");
        }
        OperationKey operationKey = new OperationKey(playerId, operationId);
        if (blockingTransactions.containsKey(operationKey)) return Optional.empty();
        String transactionId = UUID.randomUUID().toString();
        Entry entry = new Entry(transactionId, nextSequence++, playerId, operationId,
                skillId, source == null ? "" : source.name(), Status.PREPARED,
                before, target == null ? SkillTreeData.Progress.empty() : target, now, now, "");
        entries.put(transactionId, entry);
        blockingTransactions.put(operationKey, transactionId);
        trim();
        setDirty();
        return Optional.of(entry);
    }

    public synchronized Optional<Entry> find(String transactionId) {
        return Optional.ofNullable(entries.get(transactionId));
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public synchronized List<Entry> pending() {
        return entries.values().stream().filter(entry -> entry.status() == Status.PREPARED).toList();
    }

    /** Returns whether an operation is still being processed or has already committed. */
    public synchronized boolean hasBlockingOperation(UUID playerId, String operationId) {
        return playerId != null && operationId != null && !operationId.isBlank()
                && blockingTransactions.containsKey(new OperationKey(playerId, operationId));
    }

    /** Lightweight journal counters for administrator diagnostics. */
    public synchronized Summary summary() {
        int prepared = 0;
        int committed = 0;
        int rolledBack = 0;
        for (Entry entry : entries.values()) {
            switch (entry.status()) {
                case PREPARED -> prepared++;
                case COMMITTED -> committed++;
                case ROLLED_BACK -> rolledBack++;
            }
        }
        return new Summary(prepared, committed, rolledBack);
    }

    /** Marks a prepared mutation committed. Repeating the same transition is harmless. */
    public synchronized Entry commit(String transactionId, SkillTreeData.Progress after, long now) {
        Entry current = require(transactionId);
        if (current.status() == Status.COMMITTED) return current;
        if (current.status() != Status.PREPARED) {
            throw new IllegalStateException("Invalid skill XP transaction transition: "
                    + current.status() + " -> COMMITTED");
        }
        if (after == null) throw new IllegalArgumentException("Committed XP snapshot is required");
        Entry next = current.with(Status.COMMITTED, after, now, "");
        entries.put(transactionId, next);
        setDirty();
        return next;
    }

    /** Persists the current journal and associated SavedData before exposing the next transaction phase. */
    public void flush(MinecraftServer server) {
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is unavailable while saving skill XP transactions");
        }
        overworld.getDataStorage().saveAndJoin();
    }

    /** Marks a prepared mutation rolled back. Repeating the same transition is harmless. */
    public synchronized Entry rollback(String transactionId, String reason, long now) {
        Entry current = require(transactionId);
        if (current.status() == Status.ROLLED_BACK) return current;
        if (current.status() != Status.PREPARED) {
            throw new IllegalStateException("Invalid skill XP transaction transition: "
                    + current.status() + " -> ROLLED_BACK");
        }
        Entry next = current.with(Status.ROLLED_BACK, current.after(), now,
                reason == null ? "" : reason);
        entries.put(transactionId, next);
        blockingTransactions.remove(new OperationKey(current.playerId(), current.operationId()), transactionId);
        setDirty();
        return next;
    }

    /**
     * Restores every PREPARED snapshot on server start.  This method is deliberately conservative:
     * terminal entries are never reclassified and malformed entries remain in the journal.
     */
    public synchronized RecoveryReport reconcileStartup(MinecraftServer server) {
        if (server == null) return new RecoveryReport(0, 0);
        int reconciled = 0;
        int failed = 0;
        SkillTreeData data;
        try {
            data = SkillTreeData.get(server);
        } catch (RuntimeException exception) {
            OperationalErrorReporter.global().warn(
                    OperationalErrorReporter.Context.forModule(ModuleId.SKILLS, "xp_transaction_reconcile")
                            .withState("UNAVAILABLE")
                            .withRecoveryAction("transactions_retained_for_retry"), exception);
            return new RecoveryReport(0, pending().size());
        }
        Map<ProgressKey, List<Entry>> streams = new LinkedHashMap<>();
        for (Entry entry : entries.values()) {
            if (entry.status() == Status.PREPARED) {
                streams.computeIfAbsent(new ProgressKey(entry.playerId(), entry.skillId()), ignored -> new ArrayList<>())
                        .add(entry);
            }
        }
        for (Map.Entry<ProgressKey, List<Entry>> stream : streams.entrySet()) {
            List<Entry> prepared = stream.getValue().stream()
                    .sorted(java.util.Comparator.comparingLong(Entry::sequence)).toList();
            Entry first = prepared.getFirst();
            try {
                SkillTreeData.Progress current = data.progress(first.playerId(), first.skillId());
                int matchedSnapshot = matchingSnapshotIndex(prepared, current);
                if (matchedSnapshot >= 0) {
                    Entry target = prepared.getLast();
                    if (matchedSnapshot < prepared.size()) {
                        SkillTreeData.Progress base = snapshotAt(prepared, matchedSnapshot);
                        data.replace(target.playerId(), target.skillId(), mergeXpProgress(current, base, target.after()));
                    }
                    long now = System.currentTimeMillis();
                    for (Entry entry : prepared) {
                        commit(entry.transactionId(), entry.after(), now);
                    }
                    reconciled += prepared.size();
                } else {
                    failed++;
                    OperationalErrorReporter.global().warn(
                            OperationalErrorReporter.Context.forModule(ModuleId.SKILLS, "xp_transaction_reconcile")
                                    .withPlayer(first.playerId())
                                    .withWorld("overworld")
                                    .withState("PREPARED_MISMATCH")
                                    .withParameters(Map.of("skill", first.skillId(), "operationId", first.operationId(),
                                            "pendingTransactions", Integer.toString(prepared.size())))
                                    .withRecoveryAction("prepared_transaction_retained_for_manual_review"),
                            new IllegalStateException("Current skill progress does not match the transaction snapshots"));
                }
            } catch (RuntimeException exception) {
                failed++;
                OperationalErrorReporter.global().warn(
                        OperationalErrorReporter.Context.forModule(ModuleId.SKILLS, "xp_transaction_reconcile")
                                .withPlayer(first.playerId())
                                .withWorld("overworld")
                                .withState(first.status().name())
                                .withParameters(Map.of("skill", first.skillId(), "operationId", first.operationId()))
                                .withRecoveryAction("prepared_transaction_retained"), exception);
            }
        }
        if (reconciled > 0) {
            try {
                server.getLevel(Level.OVERWORLD).getDataStorage().saveAndJoin();
            } catch (RuntimeException exception) {
                OperationalErrorReporter.global().warn(
                        OperationalErrorReporter.Context.forModule(ModuleId.SKILLS, "xp_transaction_reconcile_flush")
                                .withState("ROLLED_BACK")
                                .withRecoveryAction("journal_kept_dirty_for_next_save"), exception);
            }
        }
        return new RecoveryReport(reconciled, failed);
    }

    /** Determines startup recovery without mutating a player's current progress. */
    static RecoveryAction recoveryAction(Entry entry, SkillTreeData.Progress current) {
        if (entry == null || entry.status() != Status.PREPARED || current == null) return RecoveryAction.RETAIN;
        if (current.equals(entry.after())) return RecoveryAction.COMMIT_CONFIRMED;
        if (current.equals(entry.before())) return RecoveryAction.COMMIT_TARGET;
        return RecoveryAction.RETAIN;
    }

    /** Validates a sequential PREPARED chain and confirms its latest target from any known snapshot. */
    static RecoveryAction recoveryAction(List<Entry> stream, SkillTreeData.Progress current) {
        return matchingSnapshotIndex(stream, current) >= 0 ? RecoveryAction.COMMIT_TARGET : RecoveryAction.RETAIN;
    }

    private static int matchingSnapshotIndex(List<Entry> stream, SkillTreeData.Progress current) {
        if (stream == null || stream.isEmpty() || current == null) return -1;
        Entry first = stream.getFirst();
        if (first == null || first.status() != Status.PREPARED) return -1;
        int match = sameXpState(current, first.before()) ? 0 : -1;
        SkillTreeData.Progress previous = first.before();
        for (int index = 0; index < stream.size(); index++) {
            Entry entry = stream.get(index);
            if (entry == null || entry.status() != Status.PREPARED || !sameXpState(previous, entry.before())) {
                return -1;
            }
            previous = entry.after();
            if (sameXpState(current, previous)) match = index + 1;
        }
        return match;
    }

    private static SkillTreeData.Progress snapshotAt(List<Entry> stream, int index) {
        return index == 0 ? stream.getFirst().before() : stream.get(index - 1).after();
    }

    /** Applies only the durable XP delta while preserving unrelated point and cooldown changes. */
    static SkillTreeData.Progress mergeXpProgress(SkillTreeData.Progress current,
                                                   SkillTreeData.Progress before,
                                                   SkillTreeData.Progress target) {
        int earnedPoints = Math.max(0, target.availablePoints() - before.availablePoints());
        int available = current.availablePoints() > Integer.MAX_VALUE - earnedPoints
                ? Integer.MAX_VALUE : current.availablePoints() + earnedPoints;
        java.util.Set<String> unlocked = new java.util.HashSet<>(current.unlockedSkills());
        unlocked.addAll(target.unlockedSkills());
        return new SkillTreeData.Progress(target.level(), target.currentXp(), target.totalXp(), available,
                current.attributePoints(), current.skillPoints(), current.rewardPoints(), current.masteryPoints(), unlocked,
                target.overflowXp(), target.dailyXp(), target.dailyEpochDay(),
                current.ultimateCooldownUntilEpochMillis(), current.skillLevels(),
                current.activeCooldownUntilEpochMillis(), current.skillResetCooldownUntilEpochMillis());
    }

    private static boolean sameXpState(SkillTreeData.Progress first, SkillTreeData.Progress second) {
        return first != null && second != null && first.level() == second.level()
                && first.currentXp() == second.currentXp() && first.totalXp() == second.totalXp()
                && first.overflowXp() == second.overflowXp() && first.dailyXp() == second.dailyXp()
                && first.dailyEpochDay() == second.dailyEpochDay();
    }

    private Entry require(String transactionId) {
        Entry entry = entries.get(transactionId);
        if (entry == null) throw new IllegalArgumentException("Unknown skill XP transaction: " + transactionId);
        return entry;
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            boolean removed = false;
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Entry> candidate = iterator.next();
                if (candidate.getValue().status() == Status.PREPARED) continue;
                iterator.remove();
                blockingTransactions.remove(new OperationKey(candidate.getValue().playerId(),
                        candidate.getValue().operationId()), candidate.getKey());
                removed = true;
                break;
            }
            // A pending transaction is recovery evidence and must never be discarded to satisfy
            // a retention limit. In normal operation mutations are synchronous, so this only
            // permits a temporary overage after an interrupted prior shutdown.
            if (!removed) return;
        }
    }

    private void rebuildBlockingTransactions() {
        blockingTransactions.clear();
        for (Entry entry : entries.values()) {
            if (entry.status() != Status.ROLLED_BACK) {
                blockingTransactions.put(new OperationKey(entry.playerId(), entry.operationId()), entry.transactionId());
            }
        }
    }

    static SkillXpTransactionData fromTag(CompoundTag root) {
        SkillXpTransactionData data = new SkillXpTransactionData();
        CompoundTag tags = root.getCompoundOrEmpty(ENTRIES_KEY);
        for (String key : tags.keySet()) {
            CompoundTag tag = tags.getCompoundOrEmpty(key);
            try {
                Entry entry = new Entry(key, Math.max(1L, tag.getLongOr("sequence", tag.getLongOr("created_at", 1L))),
                        UUID.fromString(tag.getStringOr("player", "")),
                        tag.getStringOr("operation", ""), tag.getStringOr("skill", ""),
                        tag.getStringOr("source", ""), Status.parse(tag.getStringOr("status", "")),
                        SkillTreeData.decodeProgress(tag.getCompoundOrEmpty("before")),
                        SkillTreeData.decodeProgress(tag.getCompoundOrEmpty("after")),
                        tag.getLongOr("created_at", 0L), tag.getLongOr("updated_at", 0L),
                        tag.getStringOr("reason", ""));
                data.entries.put(key, entry);
                data.nextSequence = Math.max(data.nextSequence, entry.sequence() + 1L);
            } catch (RuntimeException ignored) {
                // Keep malformed data out of the active map; the main cloud journal follows the
                // same fail-safe policy and reports the problem through the regular loader.
            }
        }
        data.trim();
        data.rebuildBlockingTransactions();
        return data;
    }

    static CompoundTag toTag(SkillXpTransactionData data) {
        CompoundTag root = new CompoundTag();
        CompoundTag tags = new CompoundTag();
        for (Entry entry : data.entries.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("sequence", entry.sequence());
            tag.putString("player", entry.playerId().toString());
            tag.putString("operation", entry.operationId());
            tag.putString("skill", entry.skillId());
            tag.putString("source", entry.source());
            tag.putString("status", entry.status().name());
            tag.putLong("created_at", entry.createdAt());
            tag.putLong("updated_at", entry.updatedAt());
            if (!entry.reason().isBlank()) tag.putString("reason", entry.reason());
            tag.put("before", SkillTreeData.encodeProgress(entry.before()));
            tag.put("after", SkillTreeData.encodeProgress(entry.after()));
            tags.put(entry.transactionId(), tag);
        }
        root.put(ENTRIES_KEY, tags);
        return root;
    }

    public enum Status {
        PREPARED,
        COMMITTED,
        ROLLED_BACK;

        static Status parse(String value) {
            try {
                return value == null ? PREPARED : valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown skill XP transaction status: " + value, exception);
            }
        }
    }

    enum RecoveryAction {
        COMMIT_CONFIRMED,
        COMMIT_TARGET,
        RETAIN
    }

    public record Entry(String transactionId, long sequence, UUID playerId, String operationId, String skillId,
                        String source, Status status, SkillTreeData.Progress before,
                        SkillTreeData.Progress after, long createdAt, long updatedAt, String reason) {
        public Entry {
            if (transactionId == null || transactionId.isBlank() || sequence <= 0L || playerId == null
                    || operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("Skill XP transaction identity is invalid");
            }
            skillId = skillId == null ? "" : skillId.trim().toLowerCase(java.util.Locale.ROOT);
            source = source == null ? "" : source.trim();
            status = status == null ? Status.PREPARED : status;
            before = before == null ? SkillTreeData.Progress.empty() : before;
            after = after == null ? SkillTreeData.Progress.empty() : after;
            createdAt = Math.max(0L, createdAt);
            updatedAt = Math.max(createdAt, updatedAt);
            reason = reason == null ? "" : reason.trim();
        }

        Entry with(Status nextStatus, SkillTreeData.Progress nextAfter, long now, String nextReason) {
            return new Entry(transactionId, sequence, playerId, operationId, skillId, source, nextStatus,
                    before, nextAfter, createdAt, Math.max(createdAt, now), nextReason);
        }
    }

    public record RecoveryReport(int reconciled, int failed) {
    }

    public record Summary(int prepared, int committed, int rolledBack) {
    }

    private record OperationKey(UUID playerId, String operationId) {
    }

    private record ProgressKey(UUID playerId, String skillId) {
    }
}
