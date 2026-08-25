package dev.modmind.omnitools.reward;

import dev.modmind.omnitools.ModMindEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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

    public synchronized Entry entry(RewardEvent event, String rewardId) {
        return events.getOrDefault(event.id(), Map.of()).getOrDefault(normalizeRewardId(rewardId), Entry.pending());
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
    public synchronized ItemStack queueItem(RewardEvent event, String rewardId, ItemStack stack) {
        Entry current = entry(event, rewardId);
        if (!current.itemPayload().isEmpty()) {
            return decodeItem(current.itemPayload());
        }
        Tag encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack.copy()).result().orElse(null);
        if (!(encoded instanceof CompoundTag itemTag)) {
            throw new IllegalArgumentException("Could not serialize item reward " + rewardId);
        }
        events.computeIfAbsent(event.id(), ignored -> new LinkedHashMap<>())
                .put(normalizeRewardId(rewardId), current.withItem(itemTag));
        setDirty();
        return stack.copy();
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

    /** Administrative resolution only: it deliberately does not execute an omitted side effect. */
    public synchronized int resolveEvent(RewardEvent event, EntryStatus status, String reason) {
        Map<String, Entry> rewardEntries = events.get(event.id());
        if (rewardEntries == null || rewardEntries.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        rewardEntries.replaceAll((id, current) -> new Entry(status, reason, now,
                current.dispatchedAt(), current.dispatchedCommand(), "administrator_resolution",
                current.itemPayload()));
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
                        entry.getCompoundOrEmpty(ITEM_KEY)));
            }
            if (!entries.isEmpty()) {
                ledger.events.put(eventId, entries);
            }
        }
        return ledger;
    }

    private static CompoundTag toTag(RewardClaimLedger ledger) {
        CompoundTag root = new CompoundTag();
        CompoundTag events = new CompoundTag();
        for (Map.Entry<String, Map<String, Entry>> event : ledger.events.entrySet()) {
            CompoundTag eventTag = new CompoundTag();
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

    private static ItemStack decodeItem(CompoundTag itemTag) {
        return ItemStack.CODEC.parse(NbtOps.INSTANCE, itemTag).result().orElse(ItemStack.EMPTY);
    }

    public record Entry(EntryStatus status, String reason, long updatedAt, long dispatchedAt,
                        String dispatchedCommand, String audit, CompoundTag itemPayload) {
        public Entry {
            status = status == null ? EntryStatus.PENDING : status;
            reason = reason == null ? "" : reason;
            updatedAt = Math.max(0L, updatedAt);
            dispatchedAt = Math.max(0L, dispatchedAt);
            dispatchedCommand = dispatchedCommand == null ? "" : dispatchedCommand;
            audit = audit == null ? "" : audit;
            itemPayload = itemPayload == null ? new CompoundTag() : itemPayload.copy();
        }

        static Entry pending() {
            return new Entry(EntryStatus.PENDING, "", 0L, 0L, "", "", new CompoundTag());
        }

        Entry with(EntryStatus nextStatus, String nextReason, String nextAudit, long nextDispatchedAt,
                   String nextDispatchedCommand) {
            return new Entry(nextStatus, nextReason, System.currentTimeMillis(), nextDispatchedAt,
                    nextDispatchedCommand, nextAudit, itemPayload);
        }

        Entry withItem(CompoundTag item) {
            return new Entry(status, reason, System.currentTimeMillis(), dispatchedAt, dispatchedCommand, audit,
                    item);
        }
    }

    public record RecoveryAudit(int quarantinedItems, int quarantinedCommands, int awaitingDataRecovery) {
        public boolean hasFindings() {
            return quarantinedItems > 0 || quarantinedCommands > 0 || awaitingDataRecovery > 0;
        }
    }
}
