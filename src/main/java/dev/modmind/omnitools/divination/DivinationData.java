package dev.modmind.omnitools.divination;

import dev.modmind.omnitools.CheckinData;
import dev.modmind.omnitools.ModMindEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** World-persistent daily readings and idempotent draw/interpretation operation evidence. */
public final class DivinationData extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_divination";
    private static final int DATA_VERSION = 2;
    private static final int MAX_OPERATION_RECORDS = 4096;
    private static final int MAX_HISTORY_RECORDS = 365;
    public static final SavedDataType<DivinationData> TYPE = new SavedDataType<>(DATA_ID, DivinationData::new,
            CompoundTag.CODEC.xmap(DivinationData::fromTag, DivinationData::toTag), DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, PlayerRecord> players = new HashMap<>();
    private final Map<String, OperationRecord> operations = new HashMap<>();

    public static DivinationData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) throw new IllegalStateException("The overworld is not available while loading divination data");
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Forces the operation evidence and its linked SavedData changes to disk at a money boundary. */
    public void flush(MinecraftServer server) {
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while saving divination data");
        }
        overworld.getDataStorage().saveAndJoin();
    }

    /** Reads existing operation evidence without creating a new SavedData file. */
    public static java.util.Optional<DivinationData> find(MinecraftServer server) {
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        return overworld == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(overworld.getDataStorage().get(TYPE));
    }

    public synchronized Reading current(UUID playerId, long day) {
        PlayerRecord record = players.get(playerId);
        return record == null || record.current == null || record.current.day() != day ? null : record.current;
    }

    public synchronized List<Reading> history(UUID playerId, int limit) {
        PlayerRecord record = players.get(playerId);
        if (record == null || limit < 1) return List.of();
        return record.history.stream().sorted(Comparator.comparingLong(Reading::drawnAtMillis).reversed())
                .limit(Math.min(30, limit)).toList();
    }

    public synchronized DrawResult draw(UUID playerId, String playerName, long day, String operationId,
                                        DivinationConfig.Settings settings, Reading reading) {
        return draw(playerId, playerName, day, operationId, settings, reading, DrawMode.FREE);
    }

    public synchronized DrawResult draw(UUID playerId, String playerName, long day, String operationId,
                                        DivinationConfig.Settings settings, Reading reading, DrawMode mode) {
        if (playerId == null || operationId == null || operationId.isBlank() || reading == null
                || reading.day() != day || settings == null || mode == null) {
            return DrawResult.rejected(DrawStatus.INVALID_REQUEST, null);
        }
        PlayerRecord record = players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
        resetDay(record, day, settings.historyRetentionDays());
        if (operations.containsKey(operationId)) {
            return new DrawResult(DrawStatus.DUPLICATE_OPERATION, record.current, false);
        }
        if (record.current != null && !record.current.resolved()) {
            return DrawResult.rejected(DrawStatus.PENDING_INTERPRETATION, record.current);
        }
        if (mode == DrawMode.FREE) {
            if (record.drawsToday >= settings.dailyFreeDraws()) {
                return DrawResult.rejected(DrawStatus.FREE_DRAWS_EXHAUSTED, record.current);
            }
        } else {
            if (record.current == null) return DrawResult.rejected(DrawStatus.NO_READING, null);
            if (record.drawsToday >= settings.maxDailyDraws()) {
                return DrawResult.rejected(DrawStatus.DAILY_LIMIT_REACHED, record.current);
            }
        }
        OperationRecord operation = new OperationRecord(operationId, playerId,
                mode == DrawMode.REROLL ? "reroll" : "draw", OperationState.PREPARED, day,
                reading.signId(), reading.drawnAtMillis(), System.currentTimeMillis(), record.current, reading);
        operations.put(operationId, operation);
        if (record.current != null) record.history.add(record.current.withSuperseded());
        record.current = reading;
        record.drawsToday++;
        record.lastKnownName = safeName(playerName, record.lastKnownName);
        operations.put(operationId, operation.committed());
        trimOperations();
        setDirty();
        return new DrawResult(DrawStatus.DRAWN, reading, true);
    }

    /**
     * Records a paid reroll intent without changing the displayed sign.  The caller must flush
     * this PREPARED record before debiting the wallet, then call {@link #commitPreparedReroll}.
     * This is intentionally separate from {@link #draw} because wallet and divination state live
     * in different SavedData files.
     */
    public synchronized RerollPreparation prepareReroll(UUID playerId, String playerName, long day, String operationId,
                                                         DivinationConfig.Settings settings, Reading replacement) {
        if (playerId == null || operationId == null || operationId.isBlank() || replacement == null
                || replacement.day() != day || settings == null) {
            return RerollPreparation.rejected(DrawStatus.INVALID_REQUEST, null);
        }
        PlayerRecord record = players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
        resetDay(record, day, settings.historyRetentionDays());
        OperationRecord existing = operations.get(operationId);
        if (existing != null) {
            return new RerollPreparation(DrawStatus.DUPLICATE_OPERATION, existing.targetReading(), false);
        }
        boolean pending = operations.values().stream().anyMatch(operation -> operation.playerId().equals(playerId)
                && operation.day() == day && operation.state() == OperationState.PREPARED
                && operation.type().equals("reroll"));
        if (pending) return RerollPreparation.rejected(DrawStatus.DUPLICATE_OPERATION, record.current);
        if (record.current == null || !record.current.resolved()) {
            return RerollPreparation.rejected(DrawStatus.NO_READING, record.current);
        }
        if (record.drawsToday >= settings.maxDailyDraws()) {
            return RerollPreparation.rejected(DrawStatus.DAILY_LIMIT_REACHED, record.current);
        }
        OperationRecord operation = new OperationRecord(operationId, playerId, "reroll", OperationState.PREPARED,
                day, replacement.signId(), replacement.drawnAtMillis(), System.currentTimeMillis(),
                record.current, replacement);
        operations.put(operationId, operation);
        record.lastKnownName = safeName(playerName, record.lastKnownName);
        trimOperations();
        setDirty();
        return new RerollPreparation(DrawStatus.DRAWN, replacement, true);
    }

    /** Completes a previously durable paid reroll. Repeating it is harmless. */
    public synchronized DrawResult commitPreparedReroll(UUID playerId, String playerName, String operationId) {
        OperationRecord operation = operations.get(operationId);
        if (operation == null || !operation.playerId().equals(playerId) || !operation.type().equals("reroll")) {
            return DrawResult.rejected(DrawStatus.INVALID_REQUEST, null);
        }
        if (operation.state() == OperationState.COMMITTED) {
            return new DrawResult(DrawStatus.DUPLICATE_OPERATION, operation.targetReading(), false);
        }
        if (operation.state() != OperationState.PREPARED || operation.targetReading() == null) {
            return DrawResult.rejected(DrawStatus.DUPLICATE_OPERATION, null);
        }
        PlayerRecord record = players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
        if (!sameReading(record.current, operation.beforeReading())) {
            return DrawResult.rejected(DrawStatus.EXISTING_READING, record.current);
        }
        if (record.current != null) record.history.add(record.current.withSuperseded());
        record.current = operation.targetReading();
        record.drawsToday++;
        record.lastKnownName = safeName(playerName, record.lastKnownName);
        operations.put(operationId, operation.committed());
        trimOperations();
        setDirty();
        return new DrawResult(DrawStatus.DRAWN, record.current, true);
    }

    /** Rolls back a durable intent when the wallet debit could not be accepted. */
    public synchronized void rollbackPreparedReroll(UUID playerId, String operationId, String reason) {
        OperationRecord operation = operations.get(operationId);
        if (operation == null || !operation.playerId().equals(playerId) || !operation.type().equals("reroll")
                || operation.state() != OperationState.PREPARED) {
            return;
        }
        operations.put(operationId, operation.rolledBack());
        setDirty();
    }

    public synchronized InterpretResult interpret(UUID playerId, String playerName, long day, String operationId,
                                                  boolean deep, DivinationConfig.Settings settings,
                                                  DivinationConfig.Theme replacementTheme) {
        if (playerId == null || operationId == null || operationId.isBlank()) {
            return InterpretResult.rejected(InterpretStatus.INVALID_REQUEST, null);
        }
        PlayerRecord record = players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
        resetDay(record, day, settings.historyRetentionDays());
        OperationRecord prior = operations.get(operationId);
        if (prior != null) return new InterpretResult(InterpretStatus.DUPLICATE_OPERATION, record.current, false);
        Reading current = record.current;
        if (current == null) return InterpretResult.rejected(InterpretStatus.NO_READING, null);
        if (current.resolved() && (!deep || current.deepRead())) {
            return InterpretResult.rejected(InterpretStatus.ALREADY_INTERPRETED, current);
        }
        if (deep) {
            return InterpretResult.rejected(InterpretStatus.DEEP_READING_DISABLED, current);
        }
        DivinationConfig.Theme theme = deep && replacementTheme != null && replacementTheme != DivinationConfig.Theme.RANDOM
                ? replacementTheme : current.theme();
        Reading interpreted = current.withInterpretation(deep, theme, operationId);
        OperationRecord operation = new OperationRecord(operationId, playerId, deep ? "deep_interpret" : "interpret",
                OperationState.PREPARED, day, current.signId(), current.drawnAtMillis(), System.currentTimeMillis(),
                current, interpreted);
        operations.put(operationId, operation);
        record.current = interpreted;
        record.lastKnownName = safeName(playerName, record.lastKnownName);
        operations.put(operationId, operation.committed());
        trimOperations();
        setDirty();
        return new InterpretResult(deep ? InterpretStatus.DEEP_INTERPRETED : InterpretStatus.INTERPRETED,
                record.current, true);
    }

    /** Records only server-confirmed guided gameplay actions, never UI clicks or package grants. */
    public synchronized ReliefResult recordGuidedAction(UUID playerId, long day, String operationId,
                                                        DivinationConfig.Theme eventTheme) {
        if (playerId == null || operationId == null || operationId.isBlank()) return ReliefResult.none();
        PlayerRecord record = players.get(playerId);
        if (record == null || record.current == null || record.current.day() != day) return ReliefResult.none();
        Reading reading = record.current;
        if (!reading.resolved() || !reading.reliefRequired() || reading.reliefComplete()
                || reading.theme() != eventTheme || !reliefOperations(record).add(operationId)) {
            return ReliefResult.none();
        }
        int progress = Math.min(reading.reliefGoal(), reading.reliefProgress() + 1);
        boolean complete = progress >= reading.reliefGoal();
        record.current = reading.withReliefProgress(progress, complete);
        setDirty();
        return new ReliefResult(true, complete, record.current);
    }

    /** Resolves only durable PREPARED records after restart; terminal records remain immutable. */
    public synchronized RecoveryReport reconcileStartup() {
        return reconcileStartup(null);
    }

    /**
     * Reconciles paid rerolls against the wallet's idempotency marker.  If the debit reached disk,
     * the stored replacement sign is committed; otherwise the prepared intent is rolled back.
     */
    public synchronized RecoveryReport reconcileStartup(MinecraftServer server) {
        int committed = 0;
        int rolledBack = 0;
        for (Map.Entry<String, OperationRecord> entry : new ArrayList<>(operations.entrySet())) {
            OperationRecord operation = entry.getValue();
            if (operation.state() != OperationState.PREPARED) continue;
            if (operation.type().equals("reroll") && operation.targetReading() != null && server != null) {
                if (CheckinData.get(server).hasDivinationRerollCharge(operation.playerId(), operation.operationId())) {
                    DrawResult result = commitPreparedReroll(operation.playerId(), "", operation.operationId());
                    if (result.status() == DrawStatus.DRAWN || result.status() == DrawStatus.DUPLICATE_OPERATION) {
                        committed++;
                    } else {
                        // A charged operation with a conflicting page must remain PREPARED for
                        // manual inspection; marking it rolled back would hide a paid reroll.
                        continue;
                    }
                } else {
                    operations.put(entry.getKey(), operation.rolledBack());
                    rolledBack++;
                }
                continue;
            }
            PlayerRecord player = players.get(operation.playerId());
            Reading current = player == null ? null : player.current;
            boolean applied = current != null && current.day() == operation.day()
                    && current.signId().equals(operation.signId()) && current.drawnAtMillis() == operation.drawnAtMillis();
            operations.put(entry.getKey(), applied ? operation.committed() : operation.rolledBack());
            if (applied) committed++; else rolledBack++;
        }
        if (committed > 0 || rolledBack > 0) setDirty();
        return new RecoveryReport(committed, rolledBack);
    }

    /** Idempotent reward repair for readings persisted just before a process stop. */
    public synchronized int reconcileCurrencyRewards(MinecraftServer server) {
        if (server == null) return 0;
        CheckinData wallet = CheckinData.get(server);
        int applied = 0;
        for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
            List<Reading> readings = new ArrayList<>(entry.getValue().history);
            if (entry.getValue().current != null) readings.add(entry.getValue().current);
            for (Reading reading : readings) {
                if (!reading.resolved() || reading.currencyReward() <= 0L || reading.rewardOperationId().isBlank()) continue;
                CheckinData.CurrencyRewardResult result = wallet.applyRewardCurrency(entry.getKey(),
                        reading.rewardOperationId(), "divination_sign", reading.currencyReward(),
                        entry.getValue().lastKnownName);
                if (result == CheckinData.CurrencyRewardResult.APPLIED) applied++;
            }
        }
        return applied;
    }

    public synchronized OperationSummary operationSummary(String operationId) {
        OperationRecord operation = operations.get(operationId);
        return operation == null ? null : new OperationSummary(operation.operationId(), operation.playerId(), operation.type(),
                operation.state(), operation.day(), operation.updatedAtMillis());
    }

    /** Returns immutable operation evidence for diagnostics without exposing mutable records. */
    public synchronized List<OperationSummary> operations() {
        return operations.values().stream()
                .map(operation -> new OperationSummary(operation.operationId(), operation.playerId(), operation.type(),
                        operation.state(), operation.day(), operation.updatedAtMillis()))
                .sorted(Comparator.comparingLong(OperationSummary::updatedAtMillis).reversed())
                .toList();
    }

    private static Set<String> reliefOperations(PlayerRecord record) {
        if (record.reliefOperations == null) record.reliefOperations = new HashSet<>();
        return record.reliefOperations;
    }

    private static void resetDay(PlayerRecord record, long day, int retentionDays) {
        if (record.day == day) return;
        if (record.current != null) record.history.add(record.current);
        record.current = null;
        record.day = day;
        record.drawsToday = 0;
        record.reliefOperations = new HashSet<>();
        long cutoff = day - Math.max(1, retentionDays);
        record.history.removeIf(reading -> reading.day() < cutoff);
        while (record.history.size() > MAX_HISTORY_RECORDS) record.history.removeFirst();
    }

    private void trimOperations() {
        if (operations.size() <= MAX_OPERATION_RECORDS) return;
        List<OperationRecord> ordered = new ArrayList<>(operations.values());
        ordered.sort(Comparator.comparingLong(OperationRecord::updatedAtMillis));
        for (int index = 0; index < ordered.size() - MAX_OPERATION_RECORDS; index++) {
            operations.remove(ordered.get(index).operationId());
        }
    }

    private static String safeName(String value, String fallback) {
        if (value != null && !value.isBlank()) return value.substring(0, Math.min(64, value.length()));
        return fallback == null ? "" : fallback;
    }

    private static boolean sameReading(Reading left, Reading right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.day() == right.day() && left.drawnAtMillis() == right.drawnAtMillis()
                && left.signId().equals(right.signId()) && left.resolved() == right.resolved()
                && left.superseded() == right.superseded();
    }

    private static DivinationData fromTag(CompoundTag root) {
        DivinationData data = new DivinationData();
        CompoundTag players = root.getCompoundOrEmpty("players");
        for (String key : players.keySet()) {
            try {
                UUID id = UUID.fromString(key);
                CompoundTag tag = players.getCompoundOrEmpty(key);
                PlayerRecord record = new PlayerRecord();
                record.day = tag.getLongOr("day", Long.MIN_VALUE);
                record.drawsToday = Math.max(0, tag.getIntOr("draws_today", 0));
                record.lastKnownName = tag.getStringOr("name", "");
                if (tag.contains("current")) record.current = Reading.fromTag(tag.getCompoundOrEmpty("current"));
                for (int index = 0; index < tag.getListOrEmpty("history").size(); index++) {
                    tag.getListOrEmpty("history").getCompound(index).ifPresent(value -> record.history.add(Reading.fromTag(value)));
                }
                for (int index = 0; index < tag.getListOrEmpty("relief_operations").size(); index++) {
                    tag.getListOrEmpty("relief_operations").getString(index).ifPresent(record.reliefOperations::add);
                }
                data.players.put(id, record);
            } catch (IllegalArgumentException ignored) {
                // Preserve the rest of the file when one old player id is malformed.
            }
        }
        for (int index = 0; index < root.getListOrEmpty("operations").size(); index++) {
            root.getListOrEmpty("operations").getCompound(index).ifPresent(tag -> {
                OperationRecord operation = OperationRecord.fromTag(tag);
                if (operation != null) data.operations.put(operation.operationId(), operation);
            });
        }
        return data;
    }

    private static CompoundTag toTag(DivinationData data) {
        CompoundTag root = new CompoundTag();
        root.putInt("version", DATA_VERSION);
        CompoundTag players = new CompoundTag();
        data.players.forEach((id, record) -> players.put(id.toString(), record.toTag()));
        root.put("players", players);
        ListTag operations = new ListTag();
        data.operations.values().stream().sorted(Comparator.comparingLong(OperationRecord::updatedAtMillis))
                .forEach(operation -> operations.add(operation.toTag()));
        root.put("operations", operations);
        return root;
    }

    private static final class PlayerRecord {
        private long day = Long.MIN_VALUE;
        private int drawsToday;
        private String lastKnownName = "";
        private Reading current;
        private final List<Reading> history = new ArrayList<>();
        private Set<String> reliefOperations = new HashSet<>();

        private CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("day", day);
            tag.putInt("draws_today", drawsToday);
            tag.putString("name", lastKnownName);
            if (current != null) tag.put("current", current.toTag());
            ListTag entries = new ListTag();
            history.stream().sorted(Comparator.comparingLong(Reading::drawnAtMillis)).forEach(reading -> entries.add(reading.toTag()));
            tag.put("history", entries);
            ListTag operationIds = new ListTag();
            reliefOperations.stream().sorted().forEach(value -> operationIds.add(net.minecraft.nbt.StringTag.valueOf(value)));
            tag.put("relief_operations", operationIds);
            return tag;
        }
    }

    /**
     * Stored snapshot of an issued sign. Legacy relief fields remain readable so existing SavedData
     * survives the five-rank migration, but new readings always set them to zero.
     */
    public record Reading(long day, String signId, DivinationConfig.Rank rank, DivinationConfig.Theme theme,
                          long drawnAtMillis, boolean resolved, boolean deepRead, int reliefGoal,
                          int reliefProgress, boolean reliefComplete, double xpBonus, double reliefBonus,
                          boolean superseded, long currencyReward, String rewardOperationId) {
        public Reading {
            signId = signId == null ? "" : signId;
            rank = rank == null ? DivinationConfig.Rank.NEUTRAL : rank;
            theme = theme == null ? DivinationConfig.Theme.MINING : theme;
            reliefGoal = Math.max(0, reliefGoal);
            reliefProgress = Math.max(0, Math.min(reliefGoal, reliefProgress));
            xpBonus = clampModifier(xpBonus);
            reliefBonus = clampModifier(reliefBonus);
            currencyReward = Math.max(0L, currencyReward);
            rewardOperationId = rewardOperationId == null ? "" : rewardOperationId;
        }

        public boolean reliefRequired() { return reliefGoal > 0; }
        public boolean active() { return resolved && !superseded; }
        public double activeBonus(double cap) {
            if (!active()) return 0.0D;
            if (xpBonus >= 0.0D) return Math.min(Math.max(0.0D, cap), xpBonus);
            return Math.max(-0.15D, xpBonus);
        }
        Reading withInterpretation(boolean deep, DivinationConfig.Theme replacementTheme, String operationId) {
            return new Reading(day, signId, rank, replacementTheme, drawnAtMillis, true, deep || deepRead, reliefGoal,
                    reliefProgress, reliefComplete, xpBonus, reliefBonus, superseded, currencyReward, operationId);
        }
        Reading withReliefProgress(int progress, boolean complete) {
            return new Reading(day, signId, rank, theme, drawnAtMillis, resolved, deepRead, reliefGoal, progress,
                    complete, xpBonus, reliefBonus, superseded, currencyReward, rewardOperationId);
        }
        Reading withSuperseded() {
            return new Reading(day, signId, rank, theme, drawnAtMillis, resolved, deepRead, reliefGoal, reliefProgress,
                    reliefComplete, xpBonus, reliefBonus, true, currencyReward, rewardOperationId);
        }
        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("day", day); tag.putString("sign", signId); tag.putString("rank", rank.serializedName());
            tag.putString("theme", theme.serializedName()); tag.putLong("drawn_at", drawnAtMillis);
            tag.putBoolean("resolved", resolved); tag.putBoolean("deep", deepRead); tag.putInt("relief_goal", reliefGoal);
            tag.putInt("relief_progress", reliefProgress); tag.putBoolean("relief_complete", reliefComplete);
            tag.putDouble("xp_bonus", xpBonus); tag.putDouble("relief_bonus", reliefBonus); tag.putBoolean("superseded", superseded);
            tag.putLong("currency_reward", currencyReward);
            if (!rewardOperationId.isBlank()) tag.putString("reward_operation", rewardOperationId);
            return tag;
        }
        static Reading fromTag(CompoundTag tag) {
            return new Reading(tag.getLongOr("day", Long.MIN_VALUE), tag.getStringOr("sign", ""),
                    DivinationConfig.Rank.parse(tag.getStringOr("rank", "middle")),
                    DivinationConfig.Theme.parse(tag.getStringOr("theme", "mining")), tag.getLongOr("drawn_at", 0L),
                    tag.getBooleanOr("resolved", false), tag.getBooleanOr("deep", false), tag.getIntOr("relief_goal", 0),
                    tag.getIntOr("relief_progress", 0), tag.getBooleanOr("relief_complete", false),
                    tag.getDoubleOr("xp_bonus", 0.0D), tag.getDoubleOr("relief_bonus", 0.0D), tag.getBooleanOr("superseded", false),
                    tag.getLongOr("currency_reward", 0L), tag.getStringOr("reward_operation", ""));
        }
        private static double clampModifier(double value) {
            return !Double.isFinite(value) ? 0.0D : Math.max(-0.15D, Math.min(0.5D, value));
        }
    }

    private record OperationRecord(String operationId, UUID playerId, String type, OperationState state, long day,
                                   String signId, long drawnAtMillis, long updatedAtMillis,
                                   Reading beforeReading, Reading targetReading) {
        OperationRecord committed() { return new OperationRecord(operationId, playerId, type, OperationState.COMMITTED, day, signId, drawnAtMillis, System.currentTimeMillis(), beforeReading, targetReading); }
        OperationRecord rolledBack() { return new OperationRecord(operationId, playerId, type, OperationState.ROLLED_BACK, day, signId, drawnAtMillis, System.currentTimeMillis(), beforeReading, targetReading); }
        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag(); tag.putString("id", operationId); tag.putString("player", playerId.toString());
            tag.putString("type", type); tag.putString("state", state.name()); tag.putLong("day", day);
            tag.putString("sign", signId); tag.putLong("drawn_at", drawnAtMillis); tag.putLong("updated_at", updatedAtMillis);
            if (beforeReading != null) tag.put("before_reading", beforeReading.toTag());
            if (targetReading != null) tag.put("target_reading", targetReading.toTag());
            return tag;
        }
        static OperationRecord fromTag(CompoundTag tag) {
            String id = tag.getStringOr("id", "");
            String player = tag.getStringOr("player", "");
            if (id.isBlank() || player.isBlank()) return null;
            try {
                return new OperationRecord(id, UUID.fromString(player), tag.getStringOr("type", ""),
                        OperationState.valueOf(tag.getStringOr("state", "PREPARED")), tag.getLongOr("day", Long.MIN_VALUE),
                        tag.getStringOr("sign", ""), tag.getLongOr("drawn_at", 0L), tag.getLongOr("updated_at", 0L),
                        tag.contains("before_reading") ? Reading.fromTag(tag.getCompoundOrEmpty("before_reading")) : null,
                        tag.contains("target_reading") ? Reading.fromTag(tag.getCompoundOrEmpty("target_reading")) : null);
            } catch (IllegalArgumentException exception) { return null; }
        }
    }

    public enum OperationState { PREPARED, COMMITTED, ROLLED_BACK }
    public enum DrawMode { FREE, REROLL }
    public enum DrawStatus { DRAWN, INVALID_REQUEST, DUPLICATE_OPERATION, PENDING_INTERPRETATION, COOLDOWN, FREE_DRAWS_EXHAUSTED, DAILY_LIMIT_REACHED, INSUFFICIENT_CURRENCY, NO_READING, EXISTING_READING }
    public enum InterpretStatus { INTERPRETED, DEEP_INTERPRETED, INVALID_REQUEST, DUPLICATE_OPERATION, NO_READING, ALREADY_INTERPRETED, DEEP_READING_DISABLED }
    public record DrawResult(DrawStatus status, Reading reading, boolean changed) { static DrawResult rejected(DrawStatus status, Reading reading) { return new DrawResult(status, reading, false); } }
    public record RerollPreparation(DrawStatus status, Reading reading, boolean prepared) {
        static RerollPreparation rejected(DrawStatus status, Reading reading) {
            return new RerollPreparation(status, reading, false);
        }
    }
    public record InterpretResult(InterpretStatus status, Reading reading, boolean changed) { static InterpretResult rejected(InterpretStatus status, Reading reading) { return new InterpretResult(status, reading, false); } }
    public record ReliefResult(boolean changed, boolean completed, Reading reading) { static ReliefResult none() { return new ReliefResult(false, false, null); } }
    public record RecoveryReport(int committed, int rolledBack) { }
    public record OperationSummary(String operationId, UUID playerId, String type, OperationState state, long day, long updatedAtMillis) { }
}
