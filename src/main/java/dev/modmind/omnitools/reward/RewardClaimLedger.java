package dev.modmind.omnitools.reward;

import dev.modmind.omnitools.ModMindEntry;
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

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Per event/reward idempotency ledger kept with world data instead of configuration files. */
public final class RewardClaimLedger extends SavedData {
    private static final String DATA_ID = ModMindEntry.MOD_ID + "_reward_claim_ledger";
    private static final String EVENTS_KEY = "events";
    private static final String REWARDS_KEY = "rewards";
    private static final String STATUS_KEY = "status";
    private static final String REASON_KEY = "reason";
    private static final String UPDATED_AT_KEY = "updated_at";
    private static final String DISPATCHED_AT_KEY = "dispatched_at";
    private static final String DISPATCHED_COMMAND_KEY = "dispatched_command";
    private static final String AUDIT_KEY = "audit";
    private static final String ITEM_KEY = "item";
    private static final String PLAYER_NAME_KEY = "player_name";
    private static final String REWARD_TYPE_KEY = "reward_type";
    private static final String UNKNOWN_REWARD_TYPE = "unknown";

    public static final SavedDataType<RewardClaimLedger> TYPE = new SavedDataType<>(
            DATA_ID,
            RewardClaimLedger::new,
            CompoundTag.CODEC.xmap(RewardClaimLedger::fromTag, RewardClaimLedger::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    public enum EntryStatus {
        PENDING,
        APPLYING,
        GRANTED,
        BLOCKED,
        FAILED;

        static EntryStatus parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException ignored) {
                return PENDING;
            }
        }
    }

    private final Map<String, Map<String, Entry>> events = new LinkedHashMap<>();
    /** Event-level display metadata is deliberately separate from the idempotency key. */
    private final Map<String, String> eventPlayerNames = new LinkedHashMap<>();

    public static RewardClaimLedger get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld is not available while loading reward ledger");
        }
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public static RewardClaimLedger get(ServerPlayer player) {
        return get(player.level().getServer());
    }

    /**
     * Reads an already-created ledger without creating or dirtying SavedData. This is intended for
     * read-only diagnostics and returns empty when no reward has ever been recorded.
     */
    public static Optional<RewardClaimLedger> find(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(overworld.getDataStorage().get(TYPE));
    }

    public static int unresolvedEntryCount(MinecraftServer server) {
        return find(server).map(RewardClaimLedger::unresolvedEntryCount).orElse(0);
    }

    public synchronized Entry entry(RewardEvent event, String rewardId) {
        return events.getOrDefault(event.id(), Map.of()).getOrDefault(normalizeRewardId(rewardId), Entry.pending());
    }

    /** Stores harmless display metadata while preserving the stable event/reward identifiers. */
    public synchronized void registerReward(RewardEvent event, String rewardId, RewardType type, String playerName) {
        String eventId = event.id();
        String normalizedId = normalizeRewardId(rewardId);
        boolean changed = false;
        String normalizedName = normalizeText(playerName);
        if (!normalizedName.isBlank() && !normalizedName.equals(eventPlayerNames.get(eventId))) {
            eventPlayerNames.put(eventId, normalizedName);
            changed = true;
        }
        Map<String, Entry> rewardEntries = events.computeIfAbsent(eventId, ignored -> new LinkedHashMap<>());
        Entry current = rewardEntries.getOrDefault(normalizedId, Entry.pending());
        String typeName = type == null ? UNKNOWN_REWARD_TYPE : type.name().toLowerCase(Locale.ROOT);
        if (current.rewardType().equals(UNKNOWN_REWARD_TYPE)) {
            rewardEntries.put(normalizedId, current.withRewardType(typeName));
            changed = true;
        } else if (!rewardEntries.containsKey(normalizedId)) {
            rewardEntries.put(normalizedId, current);
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public synchronized boolean hasEvent(RewardEvent event) {
        return events.containsKey(event.id());
    }

    /** Returns event ids under a controlled, server-generated prefix for retry discovery. */
    public synchronized java.util.List<String> eventIdsStartingWith(String prefix) {
        return events.keySet().stream().filter(id -> id.startsWith(prefix)).toList();
    }

    public synchronized java.util.List<String> eventIds() {
        return java.util.List.copyOf(events.keySet());
    }

    public synchronized boolean removeEvent(RewardEvent event) {
        if (events.remove(event.id()) == null) {
            return false;
        }
        eventPlayerNames.remove(event.id());
        setDirty();
        return true;
    }

    public synchronized boolean isGranted(RewardEvent event, String rewardId) {
        return entry(event, rewardId).status() == EntryStatus.GRANTED;
    }

    public synchronized void mark(RewardEvent event, String rewardId, EntryStatus status, String reason) {
        Entry current = entry(event, rewardId);
        events.computeIfAbsent(event.id(), ignored -> new LinkedHashMap<>())
                .put(normalizeRewardId(rewardId), current.with(status, reason, current.audit(),
                        current.dispatchedAt(), current.dispatchedCommand()));
        setDirty();
    }

    /** Saves the exact item being delivered before checking or mutating the player inventory. */
    public synchronized ItemStack queueItem(RewardEvent event, String rewardId, ItemStack stack,
                                            HolderLookup.Provider registries) {
        Entry current = entry(event, rewardId);
        if (!current.itemPayload().isEmpty()) {
            return decodeItem(current.itemPayload(), registries);
        }
        Tag encoded = ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), stack.copy())
                .result().orElse(null);
        if (!(encoded instanceof CompoundTag itemTag)) {
            throw new IllegalArgumentException("Could not serialize item reward " + rewardId);
        }
        events.computeIfAbsent(event.id(), ignored -> new LinkedHashMap<>())
                .put(normalizeRewardId(rewardId), current.withItem(itemTag));
        setDirty();
        return stack.copy();
    }

    /** Returns the original persisted item payload, never a freshly parsed configuration reward. */
    public synchronized ItemStack queuedItem(RewardEvent event, String rewardId, HolderLookup.Provider registries) {
        return decodeItem(entry(event, rewardId).itemPayload(), registries);
    }

    /** Decodes an immutable ledger snapshot for display only. */
    public static ItemStack itemForDisplay(Entry entry, HolderLookup.Provider registries) {
        return entry == null ? ItemStack.EMPTY : decodeItem(entry.itemPayload(), registries);
    }

    /**
     * Writes the intent before a side effect. The caller must later transition the entry to a
     * terminal state or let recovery make the conservative decision for the reward type.
     */
    public synchronized void beginApplying(RewardEvent event, String rewardId, String audit) {
        Entry current = entry(event, rewardId);
        events.computeIfAbsent(event.id(), ignored -> new LinkedHashMap<>())
                .put(normalizeRewardId(rewardId), current.with(EntryStatus.APPLYING, "", audit,
                        current.dispatchedAt(), current.dispatchedCommand()));
        setDirty();
    }

    /** Records a command exactly as it was dispatched for administrator investigation. */
    public synchronized void markCommandDispatched(RewardEvent event, String rewardId, String command) {
        Entry current = entry(event, rewardId);
        long now = System.currentTimeMillis();
        events.computeIfAbsent(event.id(), ignored -> new LinkedHashMap<>())
                .put(normalizeRewardId(rewardId), current.with(EntryStatus.APPLYING, current.reason(),
                        "command_dispatched", now, command));
        setDirty();
    }

    public synchronized Map<String, Entry> entries(RewardEvent event) {
        return Map.copyOf(events.getOrDefault(event.id(), Map.of()));
    }

    /** A stable snapshot for administrator views. Corrupt legacy event ids remain inspectable. */
    public synchronized List<LedgerEntry> allEntries() {
        List<LedgerEntry> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Entry>> event : events.entrySet()) {
            UUID playerId = eventPlayerId(event.getKey()).orElse(null);
            String playerName = eventPlayerNames.getOrDefault(event.getKey(), "");
            for (Map.Entry<String, Entry> reward : event.getValue().entrySet()) {
                result.add(new LedgerEntry(event.getKey(), reward.getKey(), playerId, playerName, reward.getValue()));
            }
        }
        return List.copyOf(result);
    }

    /** Only PENDING item snapshots are safe for player-initiated delivery attempts. */
    public synchronized List<LedgerEntry> pendingItemEntries(UUID playerId) {
        return allEntries().stream()
                .filter(entry -> playerId.equals(entry.playerId()))
                .filter(entry -> entry.entry().status() == EntryStatus.PENDING)
                .filter(entry -> !entry.entry().itemPayload().isEmpty())
                .toList();
    }

    /** Counts entries without a confirmed grant, including terminal failures needing administrator review. */
    public synchronized int unresolvedEntryCount() {
        int unresolved = 0;
        for (Map<String, Entry> rewardEntries : events.values()) {
            for (Entry entry : rewardEntries.values()) {
                if (entry.status() != EntryStatus.GRANTED) {
                    unresolved++;
                }
            }
        }
        return unresolved;
    }

    /** Administrative resolution only: it deliberately does not execute an omitted side effect. */
    public synchronized ResolutionResult resolveEntry(RewardEvent event, String rewardId, EntryStatus status,
                                                       String operator) {
        if (!isAdministrativeTerminalStatus(status)) {
            return ResolutionResult.rejected("resolution status must be GRANTED or FAILED");
        }
        Map<String, Entry> rewardEntries = events.get(event.id());
        String normalizedId = normalizeRewardId(rewardId);
        if (rewardEntries == null || !rewardEntries.containsKey(normalizedId)) {
            return ResolutionResult.rejected("reward ledger entry does not exist");
        }
        Entry current = rewardEntries.get(normalizedId);
        String reason = current.reason().isBlank()
                ? (status == EntryStatus.GRANTED ? "administrator_marked_granted" : "administrator_marked_failed")
                : current.reason();
        String audit = administrativeAudit(operator, current.status(), status);
        rewardEntries.put(normalizedId, current.with(status, reason, audit,
                current.dispatchedAt(), current.dispatchedCommand()));
        setDirty();
        logAdministrativeResolution(event.id(), normalizedId, operator, current.status(), status);
        return ResolutionResult.resolved(current.status(), status, reason);
    }

    /** Retained for the existing event-level command. It never dispatches a reward side effect. */
    public synchronized int resolveEvent(RewardEvent event, EntryStatus status, String operator) {
        if (!isAdministrativeTerminalStatus(status)) {
            return 0;
        }
        Map<String, Entry> rewardEntries = events.get(event.id());
        if (rewardEntries == null || rewardEntries.isEmpty()) {
            return 0;
        }
        rewardEntries.replaceAll((id, current) -> {
            String reason = current.reason().isBlank()
                    ? (status == EntryStatus.GRANTED ? "administrator_marked_granted" : "administrator_marked_failed")
                    : current.reason();
            logAdministrativeResolution(event.id(), id, operator, current.status(), status);
            return current.with(status, reason, administrativeAudit(operator, current.status(), status),
                    current.dispatchedAt(), current.dispatchedCommand());
        });
        setDirty();
        return rewardEntries.size();
    }

    /**
     * Startup-only reconciliation. A queued item or a dispatched command may already have had an
     * external effect when the process stopped, so it is quarantined for human resolution. Pure
     * data writes (currency and titles) remain APPLYING and are reconciled idempotently on login.
     */
    public synchronized RecoveryAudit reconcileStartupApplying() {
        int quarantinedItems = 0;
        int quarantinedCommands = 0;
        int awaitingDataRecovery = 0;
        for (Map<String, Entry> rewardEntries : events.values()) {
            for (Map.Entry<String, Entry> reward : rewardEntries.entrySet()) {
                Entry entry = reward.getValue();
                if (entry.status() != EntryStatus.APPLYING) {
                    continue;
                }
                if (!entry.dispatchedCommand().isBlank()) {
                    reward.setValue(entry.with(EntryStatus.BLOCKED, "command_dispatch_outcome_unknown",
                            "startup_quarantine", entry.dispatchedAt(), entry.dispatchedCommand()));
                    quarantinedCommands++;
                } else if (!entry.itemPayload().isEmpty()) {
                    reward.setValue(entry.with(EntryStatus.BLOCKED, "item_delivery_outcome_unknown",
                            "startup_quarantine", entry.dispatchedAt(), entry.dispatchedCommand()));
                    quarantinedItems++;
                } else {
                    awaitingDataRecovery++;
                }
            }
        }
        if (quarantinedItems > 0 || quarantinedCommands > 0) {
            setDirty();
        }
        return new RecoveryAudit(quarantinedItems, quarantinedCommands, awaitingDataRecovery);
    }

    public synchronized boolean allGranted(RewardEvent event, java.util.List<RewardDefinition> rewards) {
        return rewards.stream().allMatch(reward -> isGranted(event, reward.id()));
    }

    private static String normalizeRewardId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Reward id must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static RewardClaimLedger fromTag(CompoundTag root) {
        RewardClaimLedger ledger = new RewardClaimLedger();
        CompoundTag events = root.getCompoundOrEmpty(EVENTS_KEY);
        for (String eventId : events.keySet()) {
            CompoundTag eventTag = events.getCompoundOrEmpty(eventId);
            CompoundTag rewards = eventTag.getCompoundOrEmpty(REWARDS_KEY);
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (String rewardId : rewards.keySet()) {
                CompoundTag entry = rewards.getCompoundOrEmpty(rewardId);
                entries.put(rewardId, new Entry(EntryStatus.parse(entry.getStringOr(STATUS_KEY, "PENDING")),
                        entry.getStringOr(REASON_KEY, ""), entry.getLongOr(UPDATED_AT_KEY, 0L),
                        entry.getLongOr(DISPATCHED_AT_KEY, 0L),
                        entry.getStringOr(DISPATCHED_COMMAND_KEY, ""), entry.getStringOr(AUDIT_KEY, ""),
                        entry.getCompoundOrEmpty(ITEM_KEY), entry.getStringOr(REWARD_TYPE_KEY, UNKNOWN_REWARD_TYPE)));
            }
            if (!entries.isEmpty()) {
                ledger.events.put(eventId, entries);
                String playerName = eventTag.getStringOr(PLAYER_NAME_KEY, "");
                if (!playerName.isBlank()) {
                    ledger.eventPlayerNames.put(eventId, playerName);
                }
            }
        }
        return ledger;
    }

    private static CompoundTag toTag(RewardClaimLedger ledger) {
        CompoundTag root = new CompoundTag();
        CompoundTag events = new CompoundTag();
        for (Map.Entry<String, Map<String, Entry>> event : ledger.events.entrySet()) {
            CompoundTag eventTag = new CompoundTag();
            String playerName = ledger.eventPlayerNames.get(event.getKey());
            if (playerName != null && !playerName.isBlank()) {
                eventTag.putString(PLAYER_NAME_KEY, playerName);
            }
            CompoundTag rewards = new CompoundTag();
            for (Map.Entry<String, Entry> reward : event.getValue().entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString(STATUS_KEY, reward.getValue().status().name());
                if (!reward.getValue().reason().isBlank()) {
                    entry.putString(REASON_KEY, reward.getValue().reason());
                }
                if (reward.getValue().updatedAt() > 0L) {
                    entry.putLong(UPDATED_AT_KEY, reward.getValue().updatedAt());
                }
                if (reward.getValue().dispatchedAt() > 0L) {
                    entry.putLong(DISPATCHED_AT_KEY, reward.getValue().dispatchedAt());
                }
                if (!reward.getValue().dispatchedCommand().isBlank()) {
                    entry.putString(DISPATCHED_COMMAND_KEY, reward.getValue().dispatchedCommand());
                }
                if (!reward.getValue().audit().isBlank()) {
                    entry.putString(AUDIT_KEY, reward.getValue().audit());
                }
                if (!reward.getValue().rewardType().equals(UNKNOWN_REWARD_TYPE)) {
                    entry.putString(REWARD_TYPE_KEY, reward.getValue().rewardType());
                }
                if (!reward.getValue().itemPayload().isEmpty()) {
                    entry.put(ITEM_KEY, reward.getValue().itemPayload().copy());
                }
                rewards.put(reward.getKey(), entry);
            }
            eventTag.put(REWARDS_KEY, rewards);
            events.put(event.getKey(), eventTag);
        }
        root.put(EVENTS_KEY, events);
        return root;
    }

    private static ItemStack decodeItem(CompoundTag itemTag, HolderLookup.Provider registries) {
        return ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), itemTag).result()
                .orElse(ItemStack.EMPTY);
    }

    private static Optional<UUID> eventPlayerId(String eventId) {
        String[] parts = eventId == null ? new String[0] : eventId.split(":", -1);
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(parts[1]));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isAdministrativeTerminalStatus(EntryStatus status) {
        return status == EntryStatus.GRANTED || status == EntryStatus.FAILED;
    }

    private static String administrativeAudit(String operator, EntryStatus oldStatus, EntryStatus newStatus) {
        return "operator=" + normalizeText(operator) + ";old=" + oldStatus + ";new=" + newStatus
                + ";at=" + System.currentTimeMillis();
    }

    private static void logAdministrativeResolution(String eventId, String rewardId, String operator,
                                                     EntryStatus oldStatus, EntryStatus newStatus) {
        System.out.println("[omnitools] Reward ledger resolution: operator=" + normalizeText(operator)
                + ", event=" + eventId + ", reward=" + rewardId + ", old=" + oldStatus
                + ", new=" + newStatus + ", at=" + System.currentTimeMillis());
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    public record Entry(EntryStatus status, String reason, long updatedAt, long dispatchedAt,
                        String dispatchedCommand, String audit, CompoundTag itemPayload, String rewardType) {
        public Entry {
            status = status == null ? EntryStatus.PENDING : status;
            reason = reason == null ? "" : reason;
            updatedAt = Math.max(0L, updatedAt);
            dispatchedAt = Math.max(0L, dispatchedAt);
            dispatchedCommand = dispatchedCommand == null ? "" : dispatchedCommand;
            audit = audit == null ? "" : audit;
            itemPayload = itemPayload == null ? new CompoundTag() : itemPayload.copy();
            rewardType = rewardType == null || rewardType.isBlank()
                    ? UNKNOWN_REWARD_TYPE : rewardType.trim().toLowerCase(Locale.ROOT);
        }

        static Entry pending() {
            return new Entry(EntryStatus.PENDING, "", 0L, 0L, "", "", new CompoundTag(), UNKNOWN_REWARD_TYPE);
        }

        Entry with(EntryStatus nextStatus, String nextReason, String nextAudit, long nextDispatchedAt,
                   String nextDispatchedCommand) {
            return new Entry(nextStatus, nextReason, System.currentTimeMillis(), nextDispatchedAt,
                    nextDispatchedCommand, nextAudit, itemPayload, rewardType);
        }

        Entry withItem(CompoundTag item) {
            return new Entry(status, reason, System.currentTimeMillis(), dispatchedAt, dispatchedCommand, audit,
                    item, rewardType);
        }

        Entry withRewardType(String type) {
            return new Entry(status, reason, System.currentTimeMillis(), dispatchedAt, dispatchedCommand, audit,
                    itemPayload, type);
        }
    }

    public record LedgerEntry(String eventId, String rewardId, UUID playerId, String playerName, Entry entry) {
        public String displayPlayer() {
            return playerName == null || playerName.isBlank()
                    ? (playerId == null ? "unknown" : playerId.toString()) : playerName;
        }
    }

    public record ResolutionResult(boolean resolved, EntryStatus previousStatus, EntryStatus status, String reason) {
        static ResolutionResult rejected(String reason) {
            return new ResolutionResult(false, null, null, reason);
        }

        static ResolutionResult resolved(EntryStatus previousStatus, EntryStatus status, String reason) {
            return new ResolutionResult(true, previousStatus, status, reason);
        }
    }

    public record RecoveryAudit(int quarantinedItems, int quarantinedCommands, int awaitingDataRecovery) {
        public boolean hasFindings() {
            return quarantinedItems > 0 || quarantinedCommands > 0 || awaitingDataRecovery > 0;
        }
    }
}
