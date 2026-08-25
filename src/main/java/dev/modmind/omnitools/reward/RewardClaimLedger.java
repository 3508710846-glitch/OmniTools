package dev.modmind.omnitools.reward;

import dev.modmind.omnitools.ModMindEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
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

    public static final SavedDataType<RewardClaimLedger> TYPE = new SavedDataType<>(
            DATA_ID,
            RewardClaimLedger::new,
            CompoundTag.CODEC.xmap(RewardClaimLedger::fromTag, RewardClaimLedger::toTag),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    public enum EntryStatus {
        PENDING,
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

    public synchronized boolean isGranted(RewardEvent event, String rewardId) {
        return entry(event, rewardId).status() == EntryStatus.GRANTED;
    }

    public synchronized void mark(RewardEvent event, String rewardId, EntryStatus status, String reason) {
        events.computeIfAbsent(event.id(), ignored -> new LinkedHashMap<>())
                .put(normalizeRewardId(rewardId), new Entry(status, reason));
        setDirty();
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
                        entry.getStringOr(REASON_KEY, "")));
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
                rewards.put(reward.getKey(), entry);
            }
            eventTag.put(REWARDS_KEY, rewards);
            events.put(event.getKey(), eventTag);
        }
        root.put(EVENTS_KEY, events);
        return root;
    }

    public record Entry(EntryStatus status, String reason) {
        public Entry {
            status = status == null ? EntryStatus.PENDING : status;
            reason = reason == null ? "" : reason;
        }

        static Entry pending() {
            return new Entry(EntryStatus.PENDING, "");
        }
    }
}
